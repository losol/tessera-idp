#!/usr/bin/env python3
"""Packaged Keycloak/Mailpit regression smoke; see smoke-default-themes.sh.

Stdlib only. Can also target disposable native runtimes via KEYCLOAK_URL,
MAILPIT_URL, SMTP_HOST/PORT, THEME_JAR and KC_BOOTSTRAP_ADMIN_PASSWORD.
Creates and deletes its own realm; never sets any realm theme fields.
"""
import hashlib
import http.cookiejar
import json
import os
import re
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoopbackCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        # Keycloak sets Secure on trusted loopback HTTP, which browsers support
        # but Python's stdlib cookie jar otherwise refuses to send.
        if urllib.parse.urlsplit(request.full_url).hostname in ("localhost", "127.0.0.1", "::1"):
            return True
        return super().return_ok_secure(cookie, request)


class Http:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar(LoopbackCookiePolicy())

    def request(self, url, *, method="GET", data=None, form=None, token=None, follow=True, accept=None):
        headers = {}
        if accept:
            headers["Accept"] = accept
        if data is not None:
            data = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        handlers = [urllib.request.HTTPCookieProcessor(self.cookies)]
        if not follow:
            handlers.append(NoRedirect())
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            response = urllib.request.build_opener(*handlers).open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read(), response.url


def wait_for(url):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if Http().request(url)[0] == 200:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Runtime not ready: {url}")


class DefaultThemes(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = os.environ.get("KEYCLOAK_URL", "http://127.0.0.1:8080").rstrip("/")
        cls.mailpit = os.environ.get("MAILPIT_URL", "http://127.0.0.1:8025").rstrip("/")
        for url in (cls.base, cls.mailpit):
            if urllib.parse.urlsplit(url).hostname not in ("localhost", "127.0.0.1", "::1"):
                raise ValueError("Smoke tests require disposable loopback runtimes")
        cls.http = Http()
        wait_for(cls.base + "/realms/master/.well-known/openid-configuration")
        wait_for(cls.mailpit + "/api/v1/info")
        status, _, body, _ = cls.http.request(
            cls.base + "/realms/master/protocol/openid-connect/token", method="POST",
            form={"client_id": "admin-cli", "grant_type": "password",
                  "username": os.environ.get("KC_BOOTSTRAP_ADMIN_USERNAME", "admin"),
                  "password": os.environ["KC_BOOTSTRAP_ADMIN_PASSWORD"]})
        if status != 200:
            raise RuntimeError(f"Bootstrap token: HTTP {status}: {body!r}")
        cls.token = json.loads(body)["access_token"]
        info = cls.api("GET", "/serverinfo")
        version = os.environ.get("KEYCLOAK_VERSION", "26.8.2")
        if info["systemInfo"]["version"] != version:
            raise AssertionError(f"Expected Keycloak {version}, got {info['systemInfo']['version']}")
        print(f"\nKeycloak {version}; Mailpit: {cls.mailpit}", flush=True)
        cls.realm = "default-theme-smoke-" + uuid.uuid4().hex[:12]
        cls.path = "/realms/" + cls.realm
        cls.email = cls.realm + "@example.test"
        cls.callback = cls.base + "/smoke-callback"
        # Deliberately omit loginTheme, accountTheme, adminTheme AND emailTheme.
        cls.api("POST", "/realms", {
            "realm": cls.realm, "enabled": True, "sslRequired": "none",
            "smtpServer": {"host": os.environ.get("SMTP_HOST", "127.0.0.1"),
                           "port": os.environ.get("SMTP_PORT", "1025"),
                           "from": "tessera@example.test", "auth": "false",
                           "ssl": "false", "starttls": "false"}})
        cls.addClassCleanup(cls.api, "DELETE", cls.path)
        cls.api("POST", cls.path + "/users", {
            "username": "existing-user", "email": cls.email, "emailVerified": True,
            "enabled": True, "firstName": "Theme", "lastName": "Smoke"})
        cls.api("POST", cls.path + "/authentication/flows", {
            "alias": "tessera-smoke", "providerId": "basic-flow",
            "topLevel": True, "builtIn": False})
        executions = cls.path + "/authentication/flows/tessera-smoke/executions"
        cls.api("POST", executions + "/execution", {"provider": "tessera-otp"})
        execution = next(e for e in cls.api("GET", executions) if e["providerId"] == "tessera-otp")
        execution["requirement"] = "REQUIRED"
        cls.api("PUT", executions, execution)
        flow = next(f for f in cls.api("GET", cls.path + "/authentication/flows")
                    if f["alias"] == "tessera-smoke")
        for client in ("normal-login", "otp"):
            cls.api("POST", cls.path + "/clients", {
                "clientId": client, "enabled": True, "publicClient": True,
                "protocol": "openid-connect", "standardFlowEnabled": True,
                "redirectUris": [cls.callback],
                "authenticationFlowBindingOverrides": {"browser": flow["id"]} if client == "otp" else {}})

    @classmethod
    def api(cls, method, path, data=None):
        status, _, body, _ = cls.http.request(
            cls.base + "/admin" + path, method=method, data=data, token=cls.token)
        if status not in (200, 201, 204):
            raise AssertionError(f"Admin {method} {path}: HTTP {status}: {body!r}")
        return json.loads(body) if body else None

    def test_unset_themes(self):
        realm = self.api("GET", self.path)
        for field in ("loginTheme", "accountTheme", "adminTheme", "emailTheme"):
            self.assertNotIn(field, realm, f"{field} must be genuinely absent")
        self.assertNotIn("adminTheme", self.api("GET", "/realms/master"))
        print("Realm: all four theme fields absent; master adminTheme absent", flush=True)

    def test_packaged_theme(self):
        with zipfile.ZipFile(os.environ["THEME_JAR"]) as jar:
            metadata = json.loads(jar.read("META-INF/keycloak-themes.json"))
            ratio = next(t for t in metadata["themes"] if t["name"] == "ratio")
            self.assertEqual(set(ratio["types"]), {"login", "account", "admin", "email"})
            for theme_type, parent in {"account": "keycloak.v3", "admin": "keycloak.v2", "email": "keycloak"}.items():
                prefix = f"theme/ratio/{theme_type}/"
                self.assertEqual([n for n in jar.namelist() if n.startswith(prefix) and not n.endswith("/")],
                                 [prefix + "theme.properties"])
                self.assertEqual(jar.read(prefix + "theme.properties").decode().strip(), f"parent={parent}")
            self.assertIn("nb", jar.read("theme/ratio/login/theme.properties").decode())
            self.assertIn("theme/ratio/login/messages/messages_nb.properties", jar.namelist())
        info = self.api("GET", "/serverinfo")
        for theme_type in ("login", "account", "admin", "email"):
            self.assertIn("ratio", [t["name"] for t in info["themes"][theme_type]])
        print("Packaged JAR + runtime registration: ratio login/account/admin/email; stock parents verified", flush=True)

    def console(self, path, theme_type, parent, title):
        status, headers, body, url = self.http.request(self.base + path, follow=False)
        # Keycloak 26.8.2 serves BOTH account slash forms directly with 200.
        # The JS app may subsequently redirect to login; that's a separate flow.
        self.assertEqual(status, 200, body.decode(errors="replace")[:200])
        self.assertNotIn("Location", headers)
        self.assertEqual(urllib.parse.urlsplit(url).path, path)
        html = body.decode()
        self.assertIn(title, html)
        self.assertIn('id="app"', html)
        self.assertNotIn("kcContext.pageId", html)
        # The inherited entry module must be served through ratio and be exactly
        # the stock parent's JS, not a generic error page returning HTTP 200.
        assets = re.findall(r'(?:src|href)="([^"]+\.js)"', html)
        entry = next(a for a in assets if f"/{theme_type}/ratio/" in a and "/assets/" in a)
        asset_url = urllib.parse.urljoin(self.base, entry)
        stock_url = asset_url.replace(f"/{theme_type}/ratio/", f"/{theme_type}/{parent}/")
        status, _, actual, _ = self.http.request(asset_url)
        self.assertEqual(status, 200)
        status, _, stock, _ = self.http.request(stock_url)
        self.assertEqual(status, 200)
        self.assertGreater(len(actual), 1000)
        self.assertEqual(actual, stock)
        print(f"{url}: HTTP 200 stock {parent}; JS sha256={hashlib.sha256(actual).hexdigest()}", flush=True)

    def test_account(self):
        for suffix in ("account", "account/"):
            with self.subTest(path=suffix):
                self.console(f"/realms/{self.realm}/{suffix}", "account", "keycloak.v3", "Account Management")

    def test_admin(self):
        self.console("/admin/master/console/", "admin", "keycloak.v2", "Keycloak Administration")

    def login(self, client):
        http = Http()
        query = urllib.parse.urlencode({"client_id": client, "redirect_uri": self.callback,
                                       "response_type": "code", "scope": "openid", "state": "theme-smoke"})
        status, _, body, _ = http.request(self.base + f"/realms/{self.realm}/protocol/openid-connect/auth?" + query)
        self.assertEqual(status, 200, body[:500])
        html = body.decode()
        self.assertIn('kcContext.themeName = "ratio"', html)
        return http, html

    def test_login(self):
        _, html = self.login("normal-login")
        self.assertIn('kcContext.pageId = "login.ftl"', html)
        print("Normal login: HTTP 200, Ratio login.ftl", flush=True)

    def test_unknown_realm(self):
        status, _, body, _ = self.http.request(
            self.base + f"/realms/missing-{self.realm}/protocol/openid-connect/auth", accept="text/html")
        self.assertEqual(status, 404)
        html = body.decode()
        self.assertIn('kcContext.themeName = "ratio"', html)
        self.assertIn('kcContext.pageId = "error.ftl"', html)
        print("Unknown realm: HTTP 404, Ratio error.ftl", flush=True)

    def login_action(self, html):
        action = json.loads(re.search(r'"loginAction":\s*("[^"]+")', html).group(1))
        self.assertTrue(action.startswith(self.base + f"/realms/{self.realm}/login-actions/"))
        return action

    def test_email_template_and_delivered_code(self):
        users = self.api("GET", self.path + "/users?username=existing-user&exact=true")
        self.assertEqual(len(users), 1)
        self.assertEqual(users[0]["email"], self.email)
        # Existing persisted user forces TesseraOtpAuthenticator.sendOtp through
        # EmailTemplateProvider, not its raw-address EmailSenderProvider branch.
        http, html = self.login("otp")
        self.assertIn('kcContext.pageId = "login-tessera-otp-start.ftl"', html)
        status, _, body, _ = http.request(self.login_action(html), method="POST", form={"email": self.email})
        self.assertEqual(status, 200)
        html = body.decode()
        self.assertIn('kcContext.pageId = "login-tessera-otp-code.ftl"', html)
        messages = []
        for _ in range(30):
            status, _, body, _ = self.http.request(self.mailpit + "/api/v1/search?" + urllib.parse.urlencode({"query": "to:" + self.email}))
            self.assertEqual(status, 200)
            messages = json.loads(body)["messages"]
            if messages:
                break
            time.sleep(0.2)
        self.assertEqual(len(messages), 1, "Real SMTP delivery to Mailpit required")
        status, _, body, _ = self.http.request(self.mailpit + "/api/v1/message/" + messages[0]["ID"])
        self.assertEqual(status, 200)
        message = json.loads(body)
        self.assertEqual(message["Subject"], "Your login code")
        self.assertIn(self.email, [to["Address"] for to in message["To"]])
        for field in ("Text", "HTML"):
            self.assertIn("Here is your one-time login code:", message[field])
            self.assertIn("This code is valid for 5 minutes.", message[field])
        code = re.search(r"\b[0-9]{6}\b", message["Text"]).group()
        self.assertRegex(message["HTML"], r'<h1 style="[^"]*font-family: monospace;[^"]*">' + code + "</h1>")
        status, headers, _, _ = http.request(self.login_action(html), method="POST",
                                            form={"otp-code": code}, follow=False)
        self.assertEqual(status, 302)
        location = urllib.parse.urlsplit(headers["Location"])
        self.assertEqual(location.path, "/smoke-callback")
        query = urllib.parse.parse_qs(location.query)
        self.assertEqual(query["state"], ["theme-smoke"])
        self.assertIn("code", query)
        self.assertNotIn("error", query)
        status, _, body, _ = http.request(self.base + f"/realms/{self.realm}/protocol/openid-connect/token",
            method="POST", form={"grant_type": "authorization_code", "client_id": "otp",
                                 "redirect_uri": self.callback, "code": query["code"][0]})
        self.assertEqual(status, 200, body)
        self.assertIn("access_token", json.loads(body))
        print(f"Mailpit delivered {message['ID']}: Tessera text + HTML template, six-digit code redeemed for token", flush=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
