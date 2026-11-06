#!/usr/bin/env python3
"""Emit a LOCAL TEST realm; never import into production. No real secrets."""
import argparse
import json

p = argparse.ArgumentParser()
p.add_argument('--provider', choices=['google', 'apple'], default='google')
p.add_argument('--realm', default='broker-fixture')
p.add_argument('--theme', default='ratio')
a = p.parse_args()
provider_id = 'google' if a.provider == 'google' else 'tessera-apple'

def flow(alias, execution):
    return {'alias': alias, 'providerId': 'basic-flow', 'topLevel': True, 'builtIn': False,
            'authenticationExecutions': [{'authenticator': execution, 'requirement': 'REQUIRED', 'priority': 10, 'authenticatorFlow': False}]}

def user(name, email, verified=True, **extra):
    return {'username': name, 'email': email, 'enabled': True, 'emailVerified': verified,
            'firstName': 'Existing', 'lastName': 'Person',
            'attributes': {'citizen_id': ['citizen-' + name]}, **extra}

clients = []
for name in ['fixture', 'fixture-strict']:
    clients.append({'clientId': name, 'enabled': True, 'publicClient': True, 'standardFlowEnabled': True,
                    'redirectUris': ['http://127.0.0.1:18089/app/callback'],
                    'attributes': {'tessera.login-methods': a.provider + ',otp',
                                   **({'tessera.broker.security': 'strict'} if name.endswith('strict') else {})}})

realm = {'realm': a.realm, 'enabled': True, 'sslRequired': 'none', 'loginTheme': a.theme,
         'emailTheme': 'keycloak', 'loginWithEmailAllowed': True, 'duplicateEmailsAllowed': False,
         'registrationAllowed': False, 'bruteForceProtected': True,
         'browserFlow': 'fixture-browser', 'clients': clients,
         'smtpServer': {'host': '127.0.0.1', 'port': '18090', 'from': 'fixture@example.test'},
         'authenticationFlows': [flow('tessera-first-broker', 'tessera-broker-linking'), flow('tessera-post-broker', 'tessera-broker-policy'),
            {'alias': 'fixture-browser', 'providerId': 'basic-flow', 'topLevel': True, 'builtIn': False,
             'authenticationExecutions': [{'authenticator': 'auth-cookie', 'requirement': 'ALTERNATIVE', 'priority': 10, 'authenticatorFlow': False},
                                          {'authenticator': 'identity-provider-redirector', 'requirement': 'ALTERNATIVE', 'priority': 15, 'authenticatorFlow': False},
                                          {'authenticator': 'tessera-otp', 'requirement': 'ALTERNATIVE', 'priority': 20, 'authenticatorFlow': False}]}],
         'identityProviders': [{'alias': a.provider, 'providerId': provider_id, 'enabled': True,
             'displayName': a.provider.capitalize(), 'trustEmail': False, 'storeToken': False,
             'firstBrokerLoginFlowAlias': 'tessera-first-broker', 'postBrokerLoginFlowAlias': 'tessera-post-broker',
             'config': {'clientId': 'fixture-provider-client', 'clientSecret': 'fixture-only', 'syncMode': 'IMPORT',
                        'validateSignature': 'true', 'useJwksUrl': 'true',
                        'jwksUrl': 'https://www.googleapis.com/oauth2/v3/certs' if a.provider == 'google' else 'https://appleid.apple.com/auth/keys',
                        'disableNonce': 'false', 'disableUserInfoService': 'true', 'defaultScope': 'openid email profile'}}],
         'identityProviderMappers': [{'name': 'Tessera validated identity', 'identityProviderAlias': a.provider,
                                     'identityProviderMapper': 'tessera-broker-claims', 'config': {'syncMode': 'IMPORT'}}],
         'users': [user('existing', 'existing@gmail.com'), user('external-existing', 'existing@example.org'),
             user('unverified', 'unverified@gmail.com', False),
             user('conflict', 'conflict@gmail.com', federatedIdentities=[{'identityProvider': a.provider, 'userId': 'already-linked-subject', 'userName': 'conflict'}]),
             user('protected', 'protected@gmail.com', credentials=[{'type': 'password', 'value': 'fixture-password', 'temporary': False}]),
             user('strict', 'strict@gmail.com', attributes={'citizen_id': ['citizen-strict'], 'tessera.broker.security': ['strict']})]}
print(json.dumps(realm, indent=2))
