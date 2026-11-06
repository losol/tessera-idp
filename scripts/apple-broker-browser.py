#!/usr/bin/env python3
"""Apple-specific assertions over the shared AO browser/local broker harness.

Requires broker-browser.py from #92, an isolated apple-fixture realm and the
TEST-ONLY Apple factory. Never targets a live Apple service or production realm.
Consent is automatic only in the synthetic provider; this proves broker/runtime
behavior, not AO click, keyboard, OTP entry or interactive account-linking UX.
"""
import argparse
from contextlib import contextmanager
import importlib.util
import json
import os
import sys
from pathlib import Path
import urllib.parse
import urllib.request

os.environ.setdefault('BROKER_KEYCLOAK_URL', 'http://127.0.0.1:18083')
os.environ.setdefault('BROKER_REALM', 'apple-fixture')
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('broker_browser', Path(__file__).with_name('broker-browser.py'))
browser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(browser)



def linked(snapshot, subject):
    matches = [(user_id, user) for user_id, user in snapshot.items()
               if any(link['identityProvider'] == 'apple' and link['userId'] == subject
                      for link in user['links'])]
    assert len(matches) == 1, 'Exactly one issuer/subject link must resolve'
    return matches[0]


@contextmanager
def ambiguous_accounts(accounts, enabled):
    if not enabled:
        yield
        return
    original = accounts.request('').get('duplicateEmailsAllowed', False)
    created = []
    try:
        accounts.request('', method='PUT', payload={'duplicateEmailsAllowed': True})
        for suffix in ('one', 'two'):
            username = 'ambiguous-' + suffix
            accounts.request('/users', method='POST', payload={
                'username': username, 'email': 'ambiguous@gmail.com', 'enabled': True,
                'emailVerified': True, 'firstName': 'Fixture', 'lastName': 'Ambiguous',
                'attributes': {'citizen_id': [username]}})
            created.append(username)
        yield
    finally:
        for user_id, user in accounts.state().items():
            if user['username'] in created:
                accounts.request('/users/' + user_id, method='DELETE')
        accounts.request('', method='PUT', payload={'duplicateEmailsAllowed': original})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', help='Comma-separated subset; defaults to the broker/runtime matrix')
    parser.add_argument('--native-link-boundary', action='store_true', help='Requires both TEST factories; stops at native confirmation')
    parser.add_argument('--cross-site', action='store_true', help='Require localhost Keycloak and 127.0.0.1 Apple fixture as distinct sites')
    args = parser.parse_args()
    if args.cross_site:
        assert urllib.parse.urlparse(browser.BASE).hostname == 'localhost', 'Cross-site run requires Keycloak on localhost'
        assert urllib.parse.urlparse(browser.FIXTURE).hostname == '127.0.0.1', 'Synthetic Apple must be on a different site'
    positive = ['apple-first', 'apple-return', 'apple-changed-email', 'apple-private', 'link-existing']
    negative = ['unverified', 'conflict', 'protected', 'strict', 'ambiguous', 'bad-audience', 'bad-issuer',
                'bad-nonce', 'missing-nonce', 'missing-exp', 'bad-signature', 'expired',
                'bad-state', 'missing-state', 'token-error', 'failure', 'cancel', 'user-cancelled',
                'apple-malformed-profile', 'replay']
    cases = args.cases.split(',') if args.cases else positive + negative + ['client-strict']
    unknown = set(cases) - set(positive + negative + ['client-strict'])
    if unknown:
        parser.error('Unknown Apple scenarios: ' + ', '.join(sorted(unknown)))
    accounts = browser.FixtureAdmin()
    accounts.configure_profile()
    browser.ADMIN = accounts
    apple = accounts.request('/identity-provider/instances/apple')
    apple['config']['fixtureRequireCrossSite'] = str(args.cross_site).lower()
    accounts.request('/identity-provider/instances/apple', method='PUT', payload=apple)
    for case in cases:
        if case in ('apple-first', 'apple-private'):
            subject = 'apple-new' if case == 'apple-first' else 'apple-private'
            with urllib.request.urlopen(browser.FIXTURE + '/control?' + urllib.parse.urlencode({
                    'provider': 'apple', 'reset_profile': subject}), timeout=10) as response:
                assert response.status == 200
        with ambiguous_accounts(accounts, case == 'ambiguous'):
            before = accounts.state()
            if case == 'replay':
                browser.run_case('apple', 'apple-return', success=True)
                assert before == accounts.state(), 'Replay setup must preserve existing account state'
                browser.ao('open', browser.FIXTURE + '/replay/apple')
                browser.settle('We are sorry')
                assert '/app/callback' not in browser.browser_result()['url']
            elif case in ('cancel', 'user-cancelled'):
                browser.open_login('apple', case)
                browser.settle('Access denied when authenticating with Apple')
                assert '/broker/apple/endpoint' in browser.browser_result()['url']
            elif case == 'bad-nonce':
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
            after = accounts.state()
            if case in negative or case == 'client-strict':
                assert before == after, case + ': rejection must leave all accounts, links and citizen attributes unchanged'
            elif case in ('apple-return', 'apple-changed-email'):
                assert before == after, 'Missing email/profile must resolve the existing subject without profile/citizen writes'
                assert linked(before, 'apple-new')[0] == linked(after, 'apple-new')[0]
                with urllib.request.urlopen(browser.FIXTURE + '/app/result?state=' + browser.LAST_STATE,
                                            timeout=10) as response:
                    authenticated = json.load(response)
                assert authenticated.get('sub') == linked(after, 'apple-new')[0], 'RP must authenticate the original local account'
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

    if args.cross_site:
        print(json.dumps({'cross_site': 'PASS', 'cases': len(cases), 'apple_site': browser.FIXTURE,
                          'keycloak_site': browser.BASE, 'browser_header': 'Sec-Fetch-Site: cross-site required before code exchange'}), flush=True)
    if args.native_link_boundary:
        assert os.environ.get('BROKER_EXTRA_FIXTURE_FACTORY') == 'no.losol.keycloak.tessera.fixture.FixtureGoogleIdentityProviderFactory', 'Start the disposable runtime with both TEST factories first'
        providers = accounts.request('/identity-provider/instances')
        if not any(provider['alias'] == 'google' for provider in providers):
            apple = accounts.request('/identity-provider/instances/apple')
            accounts.request('/identity-provider/instances', method='POST', payload={
                'alias': 'google', 'providerId': 'google', 'enabled': True, 'trustEmail': False,
                'firstBrokerLoginFlowAlias': apple['firstBrokerLoginFlowAlias'],
                'postBrokerLoginFlowAlias': apple['postBrokerLoginFlowAlias'],
                'config': {**apple['config'], 'jwksUrl': 'https://www.googleapis.com/oauth2/v3/certs'}})
            accounts.request('/identity-provider/instances/google/mappers', method='POST', payload={
                'name': 'Tessera validated identity', 'identityProviderAlias': 'google',
                'identityProviderMapper': 'tessera-broker-claims', 'config': {'syncMode': 'IMPORT'}})
        browser.run_case('google', 'workspace', success=True)
        before = accounts.state()
        params = urllib.parse.urlencode({'client_id': 'fixture', 'redirect_uri': browser.FIXTURE + '/app/callback',
            'response_type': 'code', 'scope': 'openid', 'state': 'native-link-boundary', 'kc_action': 'idp_link:apple'})
        browser.ao('open', browser.BASE + '/realms/' + browser.REALM + '/protocol/openid-connect/auth?' + params)
        browser.settle('Linking Apple')
        assert before == accounts.state(), 'Confirmation must not mutate target account before authorization'
        print(json.dumps({'scenario': 'native-link-boundary', 'outcome': 'authenticated confirmation reached',
            'completion': 'BLOCKED: Continue requires working AO input; end-to-end native linking remains unproven'}), flush=True)


if __name__ == '__main__':
    main()
