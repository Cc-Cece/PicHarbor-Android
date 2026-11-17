"""Per-operation protection for Android, using the canonical socket deadline layer."""
from contextlib import contextmanager
import threading
import time
import uuid
import requests
from gpmc.deadline import RequestBudget, DeadlineSession, DeadlineExceeded, current
from gpmc.execution_policy import resolve_execution
from gpmc.exceptions import ProtocolError

_local = threading.local()


class Budget(RequestBudget):
    def __init__(self, seconds, state, upload=False):
        super().__init__(uuid.uuid4().hex, seconds)
        self.state, self.upload = state, upload
        self.parent = current()
        self.progress_at = time.monotonic()
        self.bytes = -1

    def progress(self, completed):
        if completed > self.bytes:
            self.bytes = completed
            self.progress_at = time.monotonic()

    def check(self):
        super().check()
        if self.parent is not None:
            self.parent.check()
        callback = self.state['callback']
        if callback and callback.isCancelled():
            self.cancel('JOB_PAUSED')
            super().check()

    def _watch(self):
        while not self.stopped.wait(.1):
            self._observe()
            try:
                self.check()
            except DeadlineExceeded as error:
                self.cancel(error.code)
                return
            except Exception:
                self.cancel('JOB_PAUSED')
                return
            if self.upload and time.monotonic() - self.progress_at > self.state['policy']['idle_seconds']:
                self.cancel('NO_PROGRESS_TIMEOUT')
                return


class Session(DeadlineSession):
    def __enter__(self):
        self.budget.__enter__()
        return super().__enter__()

    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.budget.__exit__(*args)


def seconds(fallback=60):
    state = getattr(_local, 'state', None)
    if state is None:
        return fallback
    phase, policy = state['phase'], state['policy']
    if phase == 'uploading':
        return state['file_seconds']
    return policy['commit_seconds'] if phase in ('finalizing', 'finalizing_album', 'uploaded') else policy['query_seconds']


def session():
    state = getattr(_local, 'state', None)
    return Session(Budget(seconds(), state, upload=state['phase'] == 'uploading')) if state else requests.Session()


def progress(phase, completed):
    state = getattr(_local, 'state', None)
    if state is not None:
        state['phase'] = phase
    budget = current()
    if isinstance(budget, Budget):
        budget.check()
        budget.progress(completed)


@contextmanager
def operation(action, value, callback):
    decision = resolve_execution({'overrides': value.get('_execution'), 'size': value.get('size', 0)})
    if decision.get('ok') is False:
        raise ProtocolError(decision['error'])
    policy = decision['policy']
    previous = getattr(_local, 'state', None)
    state = dict(policy=policy, file_seconds=decision['file_seconds'], callback=callback,
                 phase='finalizing' if action == 'sync_album' else 'checking')
    _local.state = state
    limit = state['file_seconds'] if action in ('process', 'process_pair', 'fingerprint', 'media_identity') else seconds()
    try:
        with Budget(limit, state) as budget:
            yield
            budget.check()
    finally:
        _local.state = previous
