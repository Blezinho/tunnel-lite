# Tunnel Lite v2 (Android 8+)

SSH (opcionalmente dentro de TLS com SNI, ou depois de um payload HTTP) -> SOCKS5 local (127.0.0.1:1080)
-> tun2socks (hev-socks5-tunnel) -> VpnService. Captura o trafego de todos os apps.
O DNS e resolvido do lado do servidor (mapdns), sem vazar DNS.

## Compilar
Envie esta pasta para um repositorio GitHub. A aba Actions -> "Build APK" -> Run workflow
compila a parte nativa com o NDK e depois o APK. Baixe o artefacto "TunnelLite-debug-apk".

## Notas
- minSdk 26 (Android 8).
- UDP nao passa (SSH nao transporta UDP). Chamadas/jogos que usam UDP nao funcionam.
