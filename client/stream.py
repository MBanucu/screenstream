#!/usr/bin/env python3
"""Read the phone's raw screen stream from a checkout.

This is a thin wrapper so ``python3 client/stream.py`` keeps working; the
implementation and the ``screenstream-stream`` console script live in the
``screenstream_client`` package beside this file.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from screenstream_client.cli import stream_main  # noqa: E402

if __name__ == "__main__":
    sys.exit(stream_main(sys.argv[1:]))
