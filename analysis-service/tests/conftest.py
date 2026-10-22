import asyncio
import sys

if sys.platform == "win32":
    # psycopg's async mode does not support the default Windows (Proactor) event loop.
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
