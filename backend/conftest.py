import os
import sys

# Ensure the backend root (where main.py / prompt.py live) is importable
# regardless of the directory pytest is invoked from.
sys.path.insert(0, os.path.dirname(__file__))
