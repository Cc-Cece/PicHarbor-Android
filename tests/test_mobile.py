"""Offline boundary tests. These are not evidence of Google account/device acceptance."""
from contextlib import closing
import hashlib
import json
import os
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
from gpmc.mobile import MobileService, Cancelled, error_code


class MobileTests(unittest.TestCase):
    def test_quick_check_never_fetches_snapshot_or_original_or_grants_cleanup(self):
        self.api.find_remote_media_by_hash.return_value = 'remote'
        with patch.object(self.service, 'original_access') as access, patch.object(self.service, 'synchronize') as sync:
            result = json.loads(self.service.dispatch('verify_cleanup', {**self.item, 'check_mode': 'quick'}, self.bridge, self.callback))
        self.assertTrue(result['found'])
        self.assertFalse(result['eligible'])
        access.assert_not_called()
        sync.assert_not_called()
        self.assertIn('file_read', result['_metrics'])
        self.assertIn('content_query', result['_metrics'])
        self.assertNotIn('original_url', result['_metrics'])
        self.handle.close.assert_called_once()

    def test_metrics_survive_network_failure_and_contain_no_identifiers(self):
        import requests
        self.api.find_remote_media_by_hash.side_effect = requests.exceptions.ConnectionError('SECRET_URL')
        result = json.loads(self.service.dispatch('verify_cleanup', self.item, self.bridge, self.callback))
        self.assertFalse(result['ok'])
        self.assertEqual('NETWORK_ERROR', result['error'])
        self.assertEqual(1, result['_metrics']['content_query']['count'])
        self.assertNotIn('SECRET_URL', json.dumps(result))
        self.assertNotIn(self.item['uri'], json.dumps(result['_metrics']))
        self.assertNotIn(self.item['name'], json.dumps(result['_metrics']))

    def test_read_admission_cancellation_does_not_open_source(self):
        self.callback.acquireRead.return_value = False
        result = json.loads(self.service.dispatch('verify_cleanup', self.item, self.bridge, self.callback))
        self.assertEqual('CANCELLED', result['error'])
        self.bridge.open.assert_not_called()
        self.callback.releaseRead.assert_not_called()

    def test_read_permit_and_handle_released_before_cloud_request(self):
        def lookup(_):
            self.handle.close.assert_called_once()
            self.callback.releaseRead.assert_called_once()
            return None
        self.api.find_remote_media_by_hash.side_effect = lookup
        result = self.service.verify_cleanup(self.item, self.bridge, self.callback)
        self.assertFalse(result['eligible'])

    def setUp(self):
        self.cwd = Path.cwd()
        self.home = os.environ.get('HOME')
        self.temp = tempfile.TemporaryDirectory()
        self.service = MobileService(self.temp.name)
        self.service.account = 'test@example.invalid'
        self.service._schema()
        self.api = Mock()
        self.api.find_remote_media_by_hash.return_value = None
        self.api.commit_upload.return_value = 'remote'
        self.api.create_album.return_value = 'album'
        self.service.client = Mock(api=self.api)
        self.file = Path(self.temp.name) / 'new.jpg'
        self.file.write_bytes(b'fresh-original-media')
        self.handle = Mock()
        self.handle.getPath.return_value = str(self.file)
        self.handle.getSize.return_value = self.file.stat().st_size
        self.bridge = Mock()
        self.bridge.open.return_value = self.handle
        self.events = []
        self.callback = Mock()
        self.callback.isCancelled.return_value = False
        self.callback.onEvent.side_effect = lambda value: self.events.append(json.loads(value))
        self.item = dict(uri='content://test/1', name='new.jpg', album_mode='none', retries=0)

    def tearDown(self):
        os.chdir(self.cwd)
        if self.home is None: os.environ.pop('HOME', None)
        else: os.environ['HOME'] = self.home
        self.temp.cleanup()

    def cloud(self, **changes):
        self.service.full_snapshot_id = 'test-snapshot'
        self.service.full_snapshot_at = time.monotonic()
        value = dict(key='remote', size=self.file.stat().st_size, original=True, trash=0, status=100,
                     snapshot_id='test-snapshot', trash_marker_absent=False)
        value.update(changes)
        with closing(sqlite3.connect(self.service.db_path)) as db, db:
            db.execute('INSERT OR REPLACE INTO cloud VALUES(?,?)', ('remote', json.dumps(value)))
        self.api.find_remote_media_by_hash.return_value = 'remote'

    def test_default_commit_keeps_original_pixel_xl_wire(self):
        result = self.service.process(self.item, self.bridge, self.callback)
        self.assertEqual('done', result['state'])
        options = self.api.commit_upload.call_args.kwargs
        self.assertEqual('original', options['quality'])
        self.assertEqual('Pixel XL', options['model'])
        self.assertNotIn('quota', result)
        self.handle.close.assert_called_once()

    def test_content_match_does_not_transfer(self):
        self.api.find_remote_media_by_hash.return_value = 'already'
        result = self.service.process(self.item, self.bridge, self.callback)
        self.assertEqual('exists', result['state'])
        self.assertEqual('already', result['remote_key'])
        self.api.upload_file.assert_not_called()

    def test_filename_skip_has_no_remote_evidence(self):
        self.cloud(name='new.jpg')
        self.api.find_remote_media_by_hash.return_value = None
        with patch.object(self.service, 'synchronize'):
            result = self.service.process({**self.item, 'same_name': True}, self.bridge, self.callback)
        self.assertEqual('filename_skipped', result['state'])
        self.assertEqual('', result['remote_key'])
        self.api.upload_file.assert_not_called()

    def test_album_failure_preserves_upload_for_retry(self):
        self.api.create_album.side_effect = RuntimeError('private credential MUST_NOT_ESCAPE')
        result = self.service.process({**self.item, 'album_mode':'folder', 'folder':'Camera'}, self.bridge, self.callback)
        self.assertEqual('album_pending', result['state'])
        self.assertEqual('remote', result['remote_key'])
        self.assertTrue(any(event['phase'] == 'uploaded' for event in self.events))
        self.assertNotIn('MUST_NOT_ESCAPE', json.dumps(result))

    def test_album_retry_only_adds_existing_content(self):
        self.api.find_remote_media_by_hash.return_value = 'remote'
        result = self.service.process({**self.item,'remote_key':'remote','origin':'uploaded','album_mode':'existing','album_value':'album'}, self.bridge, self.callback)
        self.assertEqual('done', result['state'])
        self.api.upload_file.assert_not_called()
        self.service.client.add_to_existing_album.assert_called_once_with(['remote'], 'album')

    def test_source_mutation_during_upload_never_commits(self):
        self.api.upload_file.side_effect = lambda *args: self.file.write_bytes(b'changed')
        result = self.service.process(self.item, self.bridge, self.callback)
        self.assertEqual('SOURCE_CHANGED', result['error'])
        self.api.commit_upload.assert_not_called()

    def test_cancellation_closes_source(self):
        self.callback.isCancelled.side_effect = [False, True]
        with self.assertRaises(Cancelled): self.service.process(self.item,self.bridge,self.callback)
        self.handle.close.assert_called_once()
        self.api.upload_file.assert_not_called()

    def test_cleanup_rejects_missing_fields_and_does_not_fetch_original(self):
        for field in ('original','trash','status'):
            with self.subTest(field=field):
                self.cloud(**{field:None})
                with patch.object(self.service,'original_access') as access:
                    result = self.service.verify_cleanup(self.item,self.bridge,self.callback)
                    self.assertFalse(result['eligible'])
                    access.assert_not_called()

    def test_cleanup_rejects_trash_size_and_content_change(self):
        for changes, extra in ((dict(trash=123),{}),(dict(size=99),{}),({},dict(sha256='wrong'))):
            self.cloud(**changes)
            with patch.object(self.service,'original_access') as access:
                self.assertFalse(self.service.verify_cleanup({**self.item,**extra},self.bridge,self.callback)['eligible'])
                access.assert_not_called()

    def test_cleanup_needs_reachable_original(self):
        self.cloud()
        for reachable in (False, True):
            with patch.object(self.service,'original_access',return_value=reachable):
                result=self.service.verify_cleanup(self.item,self.bridge,self.callback)
                self.assertEqual(reachable,result['eligible'])
                self.assertEqual(hashlib.sha256(self.file.read_bytes()).hexdigest(),result['sha256'])

    def test_empty_trash_marker_needs_fresh_complete_snapshot(self):
        self.cloud(trash=None, trash_marker_absent=True)
        with patch.object(self.service,'original_access',return_value=True):
            self.assertTrue(self.service.verify_cleanup(self.item,self.bridge,self.callback)['eligible'])
            self.service.full_snapshot_at -= 301
            self.assertFalse(self.service.verify_cleanup(self.item,self.bridge,self.callback)['eligible'])
            self.service.full_snapshot_at = time.monotonic()
            self.service.full_snapshot_id = 'other-snapshot'
            self.assertFalse(self.service.verify_cleanup(self.item,self.bridge,self.callback)['eligible'])

    def test_malformed_or_missing_trash_section_is_not_empty_marker(self):
        for section in (None, {'3':'bad'}, 0, {'3':None}):
            self.service._consume({'1':{'2':{'1':'remote','2':{'16':section}}}})
            with closing(sqlite3.connect(self.service.db_path)) as db:
                value=json.loads(db.execute('SELECT payload FROM cloud').fetchone()[0])
            self.assertFalse(value['trash_marker_absent'])
        for section in (b'', '', {}, {'1':1}):
            self.service._consume({'1':{'2':{'1':'remote','2':{'16':section}}}})
            with closing(sqlite3.connect(self.service.db_path)) as db:
                value=json.loads(db.execute('SELECT payload FROM cloud').fetchone()[0])
            self.assertTrue(value['trash_marker_absent'])

    def test_cleanup_rejects_unfinished_cloud_processing(self):
        self.cloud(status=1)
        with patch.object(self.service,'original_access') as access:
            result=self.service.verify_cleanup(self.item,self.bridge,self.callback)
            self.assertEqual('CLOUD_PROCESSING',result['reason'])
            access.assert_not_called()

    def test_dispatch_rejects_old_account_before_opening_media(self):
        result=json.loads(self.service.dispatch('process',{**self.item,'_account':'other@example.invalid'},self.bridge,self.callback))
        self.assertEqual({'engine_total'},set(result.pop('_metrics')))
        self.assertEqual({'ok':False,'error':'ACCOUNT_CHANGED'},result)
        self.bridge.open.assert_not_called()

    def test_failed_full_refresh_invalidates_previous_cache_readiness(self):
        self.cloud()
        with closing(sqlite3.connect(self.service.db_path)) as db, db:
            db.execute("UPDATE state SET token='previous',ready=1 WHERE id=1")
        self.api.get_library_state.side_effect=RuntimeError('offline')
        with self.assertRaises(RuntimeError): self.service.synchronize(full=True)
        self.assertEqual('',self.service.full_snapshot_id)
        with closing(sqlite3.connect(self.service.db_path)) as db:
            self.assertEqual(('',0),db.execute('SELECT token,ready FROM state').fetchone())

    def test_absent_raw_fields_do_not_become_zero_evidence(self):
        for raw in ({'16':{}},{'16':{'3':'bad'},'10':'bad','11':'bad'}):
            self.service._consume({'1':{'2':{'1':'remote','2':raw}}})
            with closing(sqlite3.connect(self.service.db_path)) as db, db:
                value=json.loads(db.execute('SELECT payload FROM cloud').fetchone()[0])
            for field in ('original','trash','status','size'):
                self.assertIsNone(value[field])

    def test_album_index_uses_collection_title_and_media_key(self):
        self.service._consume({'1':{'3':{'1':b'album-id','2':{'5':b'Camera','7':12}}}})
        with closing(sqlite3.connect(self.service.db_path)) as db, db:
            self.assertEqual(('album-id','Camera'),db.execute('SELECT * FROM albums').fetchone())

    def test_original_access_uses_head_then_limited_range_without_reading_body(self):
        self.api.get_download_urls.return_value={'1':{'5':{'2':{'6':'https://lh3.googleusercontent.com/original'}}}}
        head=Mock(status_code=405,headers={})
        get=Mock(status_code=206,headers={'Content-Range':'bytes 0-0/20'})
        contexts=[]
        for response in (head,get):
            cm=Mock();cm.__enter__=Mock(return_value=response);cm.__exit__=Mock(return_value=False);contexts.append(cm)
        with patch('gpmc.mobile.requests.Session') as session:
            session.return_value.__enter__.return_value.request.side_effect=contexts
            self.assertTrue(self.service.original_access('remote',20))
            calls=session.return_value.__enter__.return_value.request.call_args_list
            self.assertEqual('HEAD',calls[0].args[0])
            self.assertEqual('bytes=0-0',calls[1].kwargs['headers']['Range'])
            self.assertTrue(all(call.kwargs['stream'] for call in calls))
        get.iter_content.assert_not_called()

    def test_original_urls_reject_credentials_http_and_lookalike_domains(self):
        for url in ('http://google.com/a','https://google.com.attacker.invalid/a','https://u:p@google.com/a','https://attacker.invalid/a'):
            self.assertFalse(self.service.allowed_url(url))
        self.assertTrue(self.service.allowed_url('https://lh3.googleusercontent.com/a'))

    def test_sync_refuses_incomplete_cycle(self):
        self.api.get_library_state.return_value={'1':{'6':'token','7':2}}
        self.service.client._should_trigger_next_sync.return_value=True
        with self.assertRaisesRegex(RuntimeError,'SYNC_INCOMPLETE'):self.service.synchronize()
        with closing(sqlite3.connect(self.service.db_path)) as db, db:
            self.assertEqual(0,db.execute('SELECT ready FROM state').fetchone()[0])

    def test_dispatch_never_returns_raw_exception_details(self):
        with patch.object(self.service,'process',side_effect=RuntimeError('SECRET https://private/')):
            result=json.loads(self.service.dispatch('process',self.item,self.bridge,self.callback))
        self.assertNotIn('SECRET',json.dumps(result))
        self.assertNotIn('private',json.dumps(result))
        self.assertEqual({'engine_total'},set(result.pop('_metrics')))
        self.assertEqual({'ok':False,'error':'ENGINE_ERROR'},result)
        self.assertEqual('LOGIN_REQUIRED',error_code(RuntimeError('BadAuthentication SECRET')))

    def test_account_change_waits_for_inflight_operations(self):
        entered=threading.Event()
        with self.service.operation():
            def writer():
                with self.service.operation(True): entered.set()
            thread=threading.Thread(target=writer)
            thread.start()
            self.assertFalse(entered.wait(0.05))
        self.assertTrue(entered.wait(1))
        thread.join(1)
        self.assertEqual(0,self.service.active_operations)


if __name__ == '__main__': unittest.main()
