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
import uuid
import re
import html

BASE = os.environ.get('BROKER_KEYCLOAK_URL', 'http://127.0.0.1:18082')
REALM = os.environ.get('BROKER_REALM', 'broker-fixture')
FIXTURE = 'http://127.0.0.1:18089'

class FixtureAdmin:
    """Synthetic loopback realm only; never send these credentials to a remote IdP."""
    def __init__(self):
        parsed = urllib.parse.urlparse(BASE)
        if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost') or 'fixture' not in REALM:
            raise ValueError('Admin assertions require a loopback fixture realm')
        data = urllib.parse.urlencode({'client_id': 'admin-cli', 'grant_type': 'password',
                                      'username': os.environ.get('BROKER_ADMIN_USERNAME', 'fixture-admin'),
                                      'password': os.environ.get('BROKER_ADMIN_PASSWORD', 'fixture-admin')}).encode()
        self.token = json.load(urllib.request.urlopen(BASE + '/realms/master/protocol/openid-connect/token', data))['access_token']

    def request(self, path, method='GET', payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(BASE + '/admin/realms/' + REALM + path, data=data, method=method,
                    headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def logout_all(self):
        self.request('/logout-all', method='POST')

    def configure_profile(self):
        profile = self.request('/users/profile')
        for name in ['citizen_id', 'tessera.broker.security']:
            if not any(x['name'] == name for x in profile['attributes']):
                profile['attributes'].append({'name': name, 'permissions': {'view': ['admin'], 'edit': ['admin']}})
        self.request('/users/profile', method='PUT', payload=profile)

    def state(self):
        result = {}
        for user in self.request('/users?max=100&briefRepresentation=false'):
            fields = ('id', 'username', 'email', 'emailVerified', 'enabled', 'firstName', 'lastName', 'attributes', 'requiredActions')
            entry = {name: user.get(name) for name in fields}
            entry['links'] = sorted(self.request('/users/' + user['id'] + '/federated-identity'), key=lambda x: x['identityProvider'])
            entry['credentialTypes'] = sorted(x['type'] for x in self.request('/users/' + user['id'] + '/credentials'))
            result[user['id']] = entry
        return result

ADMIN = None
LAST_ORIGIN = None
LAST_STATE = None

def ao(*args):
    completed = subprocess.run(['ao', 'browser', *args, '--json'], capture_output=True, text=True, timeout=35, check=True)
    parsed = json.loads(completed.stdout)
    if parsed.get('error') or parsed.get('success') is False:
        raise AssertionError('AO browser command failed: ' + args[0])
    return parsed.get('result', parsed)

def snapshot():
    result = ao('snapshot').get('text', '')
    # Chromium's JSON viewer exposes only formatter controls to accessibility.
    # AO's selector form is needed for its body text (page-level get is broken
    # in AO 0.14.9); still uses this session's supported AO browser command.
    if 'checkbox' in result and 'heading' not in result:
        result += '\n' + ao('get', 'text', 'body').get('text', '')
    return result

def settle(expected, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = ao('snapshot')
        text = result.get('text', '')
        if 'checkbox' in text and 'heading' not in text:
            text += '\n' + ao('get', 'text', 'body').get('text', '')
        if any(label in text for label in ([expected] if isinstance(expected, str) else expected)):
            return text
        time.sleep(0.3)
    raise AssertionError('Browser did not show expected outcome: ' + str(expected))

def open_login(provider, scenario, client='fixture', action=None):
    global LAST_ORIGIN, LAST_STATE
    LAST_ORIGIN = ao('snapshot').get('_boundary', {}).get('origin')
    LAST_STATE = 'fixture-' + str(uuid.uuid4())
    if ADMIN is not None and action is None:
        ADMIN.logout_all()
    with urllib.request.urlopen(FIXTURE + '/status?provider=' + provider) as response:
        previous_choices = json.load(response)['choices']
    with urllib.request.urlopen(FIXTURE + '/control?' + urllib.parse.urlencode({'provider': provider, 'scenario': scenario})) as response:
        assert response.status == 200
    params = {'client_id': client, 'redirect_uri': FIXTURE + '/app/callback', 'response_type': 'code',
              'scope': 'openid', 'state': LAST_STATE, 'nonce': 'fixture-nonce', 'prompt': 'login', 'kc_idp_hint': provider}
    if action:
        params['kc_action'] = action
    ao('open', BASE + '/realms/' + REALM + '/protocol/openid-connect/auth?' + urllib.parse.urlencode(params))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        with urllib.request.urlopen(FIXTURE + '/status?provider=' + provider) as response:
            if json.load(response)['choices'] > previous_choices:
                ao('wait', '--load')
                return
        time.sleep(0.2)
    raise AssertionError('Browser did not reach fixture consent for ' + scenario)

def browser_result():
    result = ao('snapshot')
    return {'text': result.get('text', ''), 'url': result.get('target', {}).get('url', '')}

def run_case(provider, scenario, success=False, client='fixture', expected_failure=None):
    before = ADMIN.state() if ADMIN is not None else None
    open_login(provider, scenario, client)
    if success:
        settle('Login complete')
        assert '/app/callback' in browser_result()['url']
    else:
        # Failures have an actual Keycloak error page; merely staying on the
        # consent/login page is not sufficient evidence of rejection.
        default_failure = 'invalid nonce' if scenario == 'bad-nonce' else (
            'Access denied when authenticating with ' + provider.capitalize()
            if scenario == 'cancel' or (scenario == 'user-cancelled' and provider == 'apple') else
            'Unexpected error when authenticating with identity provider' if scenario in ('failure', 'user-cancelled') else 'We are sorry')
        settle(expected_failure or default_failure)
        result = browser_result()
        assert '/app/callback' not in result['url']
        assert 'Login complete' not in result['text']
    if ADMIN is not None:
        after = ADMIN.state()
        if success:
            with urllib.request.urlopen(FIXTURE + '/app/result?state=' + LAST_STATE) as response:
                authenticated = json.load(response)
            assert authenticated.get('sub') in after, 'Authorization result does not identify a local account'
            expected_email = {'gmail': 'new@gmail.com', 'returning': 'new@gmail.com', 'changed-email': 'new@gmail.com',
                'missing-email': 'new@gmail.com', 'workspace': 'new@workspace.example', 'link-existing': 'existing@gmail.com',
                'apple-first': 'relay@privaterelay.appleid.com', 'apple-return': 'relay@privaterelay.appleid.com',
                'apple-private': 'relay@private.icloud.com'}.get(scenario)
            if expected_email:
                expected = next(x for x in after.values() if x['email'] == expected_email)
                assert authenticated['sub'] == expected['id'], 'Callback authenticated the wrong local account'
                if scenario == 'link-existing':
                    assert expected['id'] in before and len(before) == len(after), 'Attachment created/replaced an account'
                    assert expected.get('attributes', {}).get('citizen_id') == ['citizen-existing'], 'Fixture citizen association is missing'
        if not success:
            assert before == after, 'Rejected callback mutated account/link state'
        elif scenario in ('returning', 'changed-email', 'missing-email', 'apple-return'):
            assert before == after, 'Returning identity changed local account/link state'
        elif scenario == 'link-existing':
            for user_id, old in before.items():
                current = after[user_id].copy()
                current['links'] = old['links']
                assert current == old, 'Attachment changed existing account data'
            target = next(x for x in after.values() if x['email'] == 'existing@gmail.com')
            assert any(x['identityProvider'] == provider for x in target['links'])
    print(json.dumps({'provider': provider, 'scenario': scenario, 'client': client, 'outcome': 'PASS'}), flush=True)

def captured_link(address):
    with urllib.request.urlopen(FIXTURE + '/mail?' + urllib.parse.urlencode({'to': address})) as response:
        content = response.read().decode()
    links = re.findall(r'<a href="([^"]+)">Confirm email link', content)
    assert links, 'No signed fixture email captured'
    return html.unescape(links[-1])

def open_confirmation(link):
    assert urllib.parse.urlparse(link).hostname in ('127.0.0.1', 'localhost')
    ao('open', link)
    ao('wait', '--load')

def run_external_case(scenario):
    address = 'external@example.org' if scenario == 'external' else 'existing@example.org'
    before = ADMIN.state()
    open_login('google', scenario)
    settle(['Confirm your email address', 'Link Google'])
    assert '/app/callback' not in browser_result()['url']
    pending = ADMIN.state()
    target = next(x for x in pending.values() if x['email'] == address)
    assert target['links'] == [], 'Provider attached before fresh email confirmation'
    with urllib.request.urlopen(FIXTURE + '/app/result?state=' + LAST_STATE) as response:
        assert not json.load(response), 'Client authenticated before fresh email confirmation'
    if scenario == 'external':
        assert not target['emailVerified'], 'Untrusted Google email marked verified before local proof'
    else:
        assert target == before[target['id']], 'Fresh-proof gate changed the existing account'
        assert target['attributes']['citizen_id'] == ['citizen-external-existing']
    link = captured_link(address)
    open_confirmation(link)
    settle('Login complete')
    with urllib.request.urlopen(FIXTURE + '/app/result?state=' + LAST_STATE) as response:
        assert json.load(response)['sub'] == target['id'], 'Email proof authenticated a different account'
    after = ADMIN.state()
    linked = after[target['id']]
    assert linked['emailVerified'] and linked['email'] == address
    assert len(linked['links']) == 1 and linked['links'][0]['userId'] == ('google-external' if scenario == 'external' else 'google-external-existing')
    if scenario == 'external-existing':
        preserved = linked.copy(); preserved['links'] = target['links']
        assert preserved == target, 'Fresh email proof changed existing account/citizen data'
    # The signed action token is single use, including after a successful login.
    open_confirmation(link)
    settle(['We are sorry', 'Page has expired', 'Account linking already confirmed'])
    assert ADMIN.state() == after, 'Reused email proof mutated account/link state'
    print(json.dumps({'provider': 'google', 'scenario': scenario, 'outcome': 'PASS', 'proof': 'fresh email, correct sub, preserved account, one-use link'}), flush=True)

def run_expired_external():
    realm = ADMIN.request('')
    lifespan = realm['actionTokenGeneratedByUserLifespan']
    try:
        ADMIN.request('', method='PUT', payload={'actionTokenGeneratedByUserLifespan': 1})
        before = ADMIN.state()
        open_login('google', 'external-existing')
        settle(['Confirm your email address', 'Link Google'])
        link = captured_link('existing@example.org')
        assert ADMIN.state() == before, 'Expired-proof setup attached an identity before confirmation'
        time.sleep(2.1)
        open_confirmation(link)
        settle('Action expired. Please start again.')
        assert ADMIN.state() == before, 'Expired email proof mutated account/link state'
        with urllib.request.urlopen(FIXTURE + '/app/result?state=' + LAST_STATE) as response:
            assert not json.load(response), 'Expired proof authenticated a client'
        print(json.dumps({'provider': 'google', 'scenario': 'external-expired', 'outcome': 'PASS'}), flush=True)
    finally:
        ADMIN.request('', method='PUT', payload={'actionTokenGeneratedByUserLifespan': lifespan})

def run_action_strict(provider):
    before = ADMIN.state()
    ADMIN.logout_all()
    open_login(provider, 'gmail' if provider == 'google' else 'apple-first', action='UPDATE_PROFILE')
    settle('We are sorry')
    assert ADMIN.state() == before and '/app/callback' not in browser_result()['url']
    print(json.dumps({'provider': provider, 'scenario': 'strict-action', 'outcome': 'PASS'}), flush=True)

def run_ambiguous(provider):
    realm = ADMIN.request('')
    seeded = []
    try:
        ADMIN.request('', method='PUT', payload={'duplicateEmailsAllowed': True, 'loginWithEmailAllowed': False})
        for index in (1, 2):
            username = 'fixture-ambiguous-' + str(index)
            ADMIN.request('/users', method='POST', payload={'username': username, 'email': 'ambiguous@gmail.com',
                'enabled': True, 'emailVerified': True, 'firstName': 'Fixture', 'lastName': 'Person',
                'attributes': {'citizen_id': ['ambiguous-citizen-' + str(index)]}})
            seeded.extend(x['id'] for x in ADMIN.state().values() if x['username'] == username)
        run_case(provider, 'ambiguous')
    finally:
        for user_id in seeded:
            ADMIN.request('/users/' + user_id, method='DELETE')
        ADMIN.request('', method='PUT', payload={'duplicateEmailsAllowed': realm['duplicateEmailsAllowed'],
                                                'loginWithEmailAllowed': realm['loginWithEmailAllowed']})

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--provider', default='google', choices=['google', 'apple'])
    parser.add_argument('--cases', help='Comma-separated subset; default includes positive and negative broker cases')
    args = parser.parse_args()
    ADMIN = FixtureAdmin()
    ADMIN.configure_profile()
    positive = ['gmail', 'returning', 'changed-email', 'missing-email', 'workspace', 'link-existing'] if args.provider == 'google' else ['apple-first', 'apple-return', 'apple-private', 'link-existing']
    negative = ['unverified', 'conflict', 'protected', 'strict', 'bad-audience', 'bad-issuer', 'bad-nonce', 'missing-nonce', 'missing-exp', 'bad-signature', 'expired', 'bad-state', 'missing-state', 'token-error', 'failure', 'cancel', 'user-cancelled']
    selected = args.cases.split(',') if args.cases else positive + (['external', 'external-expired', 'external-existing'] if args.provider == 'google' else []) + negative + ['ambiguous', 'strict-action']
    for case in selected:
        if case in ('external', 'external-existing'):
            run_external_case(case)
        elif case == 'external-expired':
            run_expired_external()
        elif case == 'ambiguous':
            run_ambiguous(args.provider)
        elif case == 'strict-action':
            run_action_strict(args.provider)
        else:
            run_case(args.provider, case, success=case in positive)
    if not args.cases:
        run_case(args.provider, positive[0], success=False, client='fixture-strict')
