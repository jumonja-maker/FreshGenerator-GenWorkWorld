"""FreshGenerator release gate. Never reuse the previous terrain audit for a changed graph."""
import runpy
runpy.run_path('scripts/validate_terrain_fix.py',run_name='__main__')
