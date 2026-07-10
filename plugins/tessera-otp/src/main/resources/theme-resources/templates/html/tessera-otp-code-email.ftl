<html>
<body>
    <p>${kcSanitize(msg("tesseraOtpBody"))?no_esc}</p>
    <h1 style="font-family: monospace; letter-spacing: 0.3em; font-size: 2em;">${otp}</h1>
    <p>${kcSanitize(msg("tesseraOtpExpiration", ttlMinutes))?no_esc}</p>
</body>
</html>
