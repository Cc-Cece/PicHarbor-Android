"""Import on Application main thread; background calls use this single service."""
from gpmc.mobile import MobileService

service = None

def initialize(home, diagnostics=None):
    global service
    service = MobileService(home)
    import harbor_diagnostics as diag
    diag.initialize(diagnostics)
    diag.install(service)
    return True

def dispatch(action, payload, bridge=None, callback=None):
    import json
    import harbor_diagnostics as diag
    values = json.loads(payload) if isinstance(payload, str) else payload
    with diag.context(values.pop("_diagnostics", {})):
        try:
            return service.dispatch(action, values, bridge, callback)
        finally:
            if service.client is not None:
                diag.install_api(service.client.api)
