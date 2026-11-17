"""Generate local Grafana credentials without printing or committing them."""
from pathlib import Path
import secrets

path = Path(__file__).resolve().parents[2] / '.env'
existing = path.read_text(encoding='utf-8') if path.exists() else ''
if not any(line.startswith('GRAFANA_ADMIN_PASSWORD=') for line in existing.splitlines()):
    with path.open('a', encoding='utf-8') as output:
        if existing and not existing.endswith('\n'):
            output.write('\n')
        output.write('GRAFANA_ADMIN_PASSWORD=' + secrets.token_urlsafe(32) + '\n')
print('Grafana credential configured in the ignored local .env file.')
