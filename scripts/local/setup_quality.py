"""Create local-only Sonar database credentials without printing them."""
from pathlib import Path
import secrets

destination=Path(__file__).resolve().parents[2]/'.env'
if destination.exists():
    raise SystemExit('Existing .env preserved. Set SONAR_DB_PASSWORD there if missing.')
destination.write_text('SONAR_DB_PASSWORD='+secrets.token_urlsafe(36)+'\n',encoding='utf-8')
print('Created ignored .env for local Sonar database.')
