"""Import on Application main thread; background calls use this single service."""
from gpmc.mobile import MobileService

service = None

def initialize(home):
    global service
    service = MobileService(home)
    return True

def dispatch(action, payload, bridge=None, callback=None):
    return service.dispatch(action, payload, bridge, callback)
