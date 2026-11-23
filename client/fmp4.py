#!/usr/bin/env python3
"""Save the phone's encoded stream as a fragmented MP4, from a checkout.

This is a thin wrapper so ``python3 client/fmp4.py`` keeps working; the
implementation and the ``screenstream-fmp4`` console script live in the
``screenstream_client`` package beside this file.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from screenstream_client.cli import fmp4_main  # noqa: E402

if __name__ == "__main__":
    sys.exit(fmp4_main(sys.argv[1:]))
