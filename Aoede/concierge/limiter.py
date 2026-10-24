from security import get_real_ip
from slowapi import Limiter

limiter = Limiter(key_func=get_real_ip)
