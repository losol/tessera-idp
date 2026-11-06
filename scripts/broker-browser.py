#!/usr/bin/env python3
"""AO session-owned browser broker tests against LOCAL fixtures, never live IdPs.

Run after broker-runtime.sh; explicit test consent is automated by the local
fixture. Does not claim provider-button, keyboard or OTP interaction coverage.
"""
import argparse
import json
import os
import subprocess
import time
import urllib.parse
import urllib.request

BASE = os.environ.get('BROKER_KEYCLOAK_URL', 'http://127.0.0.1:18082')
REALM = os.environ.get('BROKER_REALM', 'broker-fixture')
FIXTURE = 'http://127.0.0.1:18089'

def ao(*args):
    completed = subprocess.run(['ao', 'browser', *args, '--json'], capture_output=True, text=True, timeout=35, check=True)
    parsed = json.loads(completed.stdout)
    if parsed.get('error') or parsed.get('success') is False:
        raise AssertionError('AO browser command failed: ' + args[0])
    return parsed.get('result', parsed)

def snapshot():
    return ao('snapshot').get('text', '')

def settle(expected, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = snapshot()
        if expected in text:
            return text
        time.sleep(0.3)
    raise AssertionError('Browser did not show expected outcome: ' + expected)

def open_login(provider, scenario, client='fixture', action=None):
    with urllib.request.urlopen(FIXTURE + '/control?' + urllib.parse.urlencode({'provider': provider, 'scenario': scenario})) as response:
        assert response.status == 200
    params = {'client_id': client, 'redirect_uri': FIXTURE + '/app/callback', 'response_type': 'code',
              'scope': 'openid', 'state': 'fixture-state', 'nonce': 'fixture-nonce', 'prompt': 'login', 'kc_idp_hint': provider}
    if action:
        params['kc_action'] = action
    ao('open', BASE + '/realms/' + REALM + '/protocol/openid-connect/auth?' + urllib.parse.urlencode(params))

def browser_result():
    result = ao('snapshot')
    return {'text': result.get('text', ''), 'url': result.get('target', {}).get('url', '')}

def run_case(provider, scenario, success=False, client='fixture'):
    open_login(provider, scenario, client)
    if success:
        settle('Login complete')
        assert '/app/callback' in browser_result()['url']
    else:
        # Failures have an actual Keycloak error page; merely staying on the
        # consent/login page is not sufficient evidence of rejection.
        settle('We are sorry')
        result = browser_result()
        assert '/app/callback' not in result['url']
        assert 'Login complete' not in result['text']
    print(json.dumps({'provider': provider, 'scenario': scenario, 'client': client, 'outcome': 'PASS'}), flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--provider', default='google', choices=['google', 'apple'])
    parser.add_argument('--cases', help='Comma-separated subset; default includes positive and negative broker cases')
    args = parser.parse_args()
    positive = ['gmail', 'returning', 'changed-email', 'missing-email', 'workspace', 'link-existing'] if args.provider == 'google' else ['apple-first', 'apple-return', 'apple-private', 'link-existing']
    negative = ['unverified', 'conflict', 'protected', 'strict', 'bad-audience', 'bad-issuer', 'bad-nonce', 'missing-nonce', 'bad-signature', 'expired', 'bad-state', 'missing-state', 'token-error', 'failure']
    selected = args.cases.split(',') if args.cases else positive + negative
    for case in selected:
        run_case(args.provider, case, success=case in positive)
    if not args.cases:
        run_case(args.provider, positive[0], success=False, client='fixture-strict')
