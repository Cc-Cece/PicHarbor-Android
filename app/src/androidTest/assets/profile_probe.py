"""Read-only profile feasibility. No raw responses, names, URLs or tokens in report."""
import json
from urllib.parse import parse_qsl, urlencode, urlparse
import requests
from picharbor_engine import service
from gpmc.mobile import MobileApi

report = {}
fields = ("firstName", "lastName", "name", "given_name", "family_name", "picture", "photoUrl", "PhotoUrl")
report["existing_auth_profile_fields"] = [key for key in fields if service.client.api.auth_response_cache.get(key)]

def inspect(api, endpoint="https://openidconnect.googleapis.com/v1/userinfo"):
    value = {}
    try:
        token = api.bearer_token
        with requests.get(endpoint,
                          headers={"Authorization": "Bearer " + token},
                          timeout=(10, 15), allow_redirects=False) as response:
            value["http_status"] = response.status_code
            if response.status_code == 200 and len(response.content) <= 65536:
                payload = response.json()
                value["has_name"] = isinstance(payload.get("name"), str) and bool(payload["name"].strip())
                value["has_picture"] = isinstance(payload.get("picture"), str) and payload["picture"].startswith("https://")
                value["has_subject"] = isinstance(payload.get("sub"), str) and bool(payload["sub"])
                value["has_given_name"] = bool(payload.get("given_name"))
                value["has_family_name"] = bool(payload.get("family_name"))
                email = payload.get("email")
                value["email_present"] = isinstance(email, str) and bool(email)
                value["email_matches"] = isinstance(email, str) and email.strip().lower() == service.account
                url = payload.get("picture", "")
                parsed = urlparse(url)
                if parsed.scheme == "https" and parsed.hostname and parsed.hostname.endswith(".googleusercontent.com") and not parsed.username:
                    with requests.get(url, timeout=(10, 15), allow_redirects=False, stream=True) as image:
                        value["avatar_http_status"] = image.status_code
                        value["avatar_is_image"] = image.headers.get("Content-Type", "").lower().startswith("image/")
                        size = 0
                        for chunk in image.iter_content(8192):
                            size += len(chunk)
                            if size > 512 * 1024: break
                        value["avatar_bytes"] = size
    except Exception as error:
        value["error_type"] = type(error).__name__
        if isinstance(error, requests.exceptions.HTTPError) and error.response is not None:
            value["http_status"] = error.response.status_code
    return value

report["existing_token"] = inspect(service.client.api)
report["existing_token_v2"] = inspect(service.client.api, "https://www.googleapis.com/oauth2/v2/userinfo")
if not (report["existing_token"].get("has_name") and report["existing_token"].get("has_picture")):
    # Isolated auxiliary token. Never mutate the Photos client's validated service.
    params = dict(parse_qsl(service.credential))
    params["service"] = "oauth2:openid https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/userinfo.email"
    auxiliary = MobileApi(urlencode(params), timeout=15)
    report["auxiliary_profile_token"] = inspect(auxiliary)
report["main_auth_unchanged"] = service.client.api.auth_data == service.credential
report_json = json.dumps(report)
