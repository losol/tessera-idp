#!/usr/bin/env python3
"""Apple-specific assertions over the shared AO browser/local broker harness.

Requires broker-browser.py from #92, an isolated apple-fixture realm and the
TEST-ONLY Apple factory. Never targets a live Apple service or production realm.
Consent is automatic only in the synthetic provider; this proves broker/runtime
behavior, not AO click, keyboard, OTP entry or interactive account-linking UX.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request

os.environ.setdefault('BROKER_KEYCLOAK_URL', 'http://127.0.0.1:18083')
os.environ.setdefault('BROKER_REALM', 'apple-fixture')
spec = importlib.util.spec_from_file_location('broker_browser', Path(__file__).with_name('broker-browser.py'))
browser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(browser)


class Accounts:
    def __init__(self):
        assert urllib.parse.urlparse(browser.BASE).hostname in ('localhost', '127.0.0.1')
        assert 'fixture' in browser.REALM, 'Only an explicitly disposable fixture realm is supported'
        form = urllib.parse.urlencode({
            'grant_type': 'password', 'client_id': 'admin-cli',
            'username': os.environ.get('BROKER_ADMIN_USERNAME', 'fixture-admin'),
            'password': os.environ.get('BROKER_ADMIN_PASSWORD', 'fixture-admin'),
        }).encode()
        request = urllib.request.Request(browser.BASE + '/realms/master/protocol/openid-connect/token', data=form)
        self.token = json.load(urllib.request.urlopen(request, timeout=10))['access_token']

    def get(self, path):
        request = urllib.request.Request(browser.BASE + '/admin/realms/' + browser.REALM + path,
                                         headers={'Authorization': 'Bearer ' + self.token})
        return json.load(urllib.request.urlopen(request, timeout=10))

    def snapshot(self):
        result = {}
        for user in self.get('/users?max=1000'):
            links = self.get('/users/' + user['id'] + '/federated-identity')
            result[user['id']] = {
                key: user.get(key) for key in ('username', 'email', 'emailVerified', 'firstName',
                                              'lastName', 'attributes', 'requiredActions')
            }
            result[user['id']]['links'] = sorted([
                {key: link.get(key) for key in ('identityProvider', 'userId', 'userName')}
                for link in links], key=lambda link: (link['identityProvider'], link['userId']))
        return result

    def logout_fixture_sessions(self, user_ids):
        # Each case represents a separate browser login. Retaining a different
        # user's SSO cookie correctly triggers Keycloak's account-switch guard.
        for user_id in user_ids:
            request = urllib.request.Request(
                browser.BASE + '/admin/realms/' + browser.REALM + '/users/' + user_id + '/logout',
                data=b'', headers={'Authorization': 'Bearer ' + self.token}, method='POST')
            with urllib.request.urlopen(request, timeout=10) as response:
                assert response.status == 204


def linked(snapshot, subject):
    matches = [(user_id, user) for user_id, user in snapshot.items()
               if any(link['identityProvider'] == 'apple' and link['userId'] == subject
                      for link in user['links'])]
    assert len(matches) == 1, 'Exactly one issuer/subject link must resolve'
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', help='Comma-separated subset; defaults to the broker/runtime matrix')
    args = parser.parse_args()
    positive = ['apple-first', 'apple-return', 'apple-private', 'link-existing']
    negative = ['unverified', 'conflict', 'protected', 'strict', 'bad-audience', 'bad-issuer',
                'bad-nonce', 'missing-nonce', 'missing-exp', 'bad-signature', 'expired',
                'bad-state', 'missing-state', 'token-error', 'failure']
    cases = args.cases.split(',') if args.cases else positive + negative + ['client-strict']
    accounts = Accounts()
    for case in cases:
        before = accounts.snapshot()
        accounts.logout_fixture_sessions(before)
        if case == 'bad-nonce':
            browser.open_login('apple', case)
            error = browser.ao('get', 'text', 'body')
            value = json.loads(error.get('text', error.get('value', '')))
            assert value == {'error': 'invalid_token', 'error_description': 'invalid nonce'}
            assert '/broker/apple/endpoint' in error['target']['url']
        elif case == 'failure':
            browser.open_login('apple', case)
            browser.settle('Unexpected error when authenticating')
            assert '/broker/apple/endpoint' in browser.browser_result()['url']
        elif case == 'client-strict':
            browser.run_case('apple', 'apple-first', success=False, client='fixture-strict')
        else:
            browser.run_case('apple', case, success=case in positive)
        after = accounts.snapshot()
        if case in negative or case == 'client-strict':
            assert before == after, case + ': rejection must leave all accounts, links and citizen attributes unchanged'
        elif case == 'apple-return':
            assert before == after, 'Missing email/profile must resolve the existing subject without profile/citizen writes'
            assert linked(before, 'apple-new')[0] == linked(after, 'apple-new')[0]
        elif case in ('apple-first', 'apple-private'):
            subject = 'apple-new' if case == 'apple-first' else 'apple-private'
            expected = 'relay@privaterelay.appleid.com' if case == 'apple-first' else 'relay@private.icloud.com'
            user_id, user = linked(after, subject)
            assert user['email'] == expected and user['emailVerified'] is True
            assert user['firstName'] == 'Fixture' and user['lastName'] == 'Person'
            assert all(after[old_id] == old_user for old_id, old_user in before.items())
            assert len(after) == len(before) + (0 if user_id in before else 1)
        elif case == 'link-existing':
            original_id, original = next((key, value) for key, value in before.items()
                                          if value['email'] == 'existing@gmail.com')
            user_id, user = linked(after, 'google-existing')  # synthetic shared fixture subject, Apple issuer
            assert user_id == original_id and len(after) == len(before)
            assert {key: value for key, value in user.items() if key != 'links'} == {
                key: value for key, value in original.items() if key != 'links'}
            assert user['attributes'] and user['attributes'].get('citizen_id'), 'Fixture must expose the original citizen association'
        print(json.dumps({'scenario': case, 'state_assertions': 'PASS', 'users_before': len(before),
                          'users_after': len(after), 'scope': 'automatic synthetic consent; real Keycloak and AO navigation'}), flush=True)


if __name__ == '__main__':
    main()
