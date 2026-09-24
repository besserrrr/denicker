DENICKER 2.0 - Forge 1.8.9 mod version of the Pika proxy denicker (no proxy needed)

BUILD: push this folder to GitHub, the Actions tab builds Denicker-2.0.jar (see .github/workflows/build.yml)
INSTALL: put the jar in .minecraft/mods, launch Forge 1.8.9, join Pika normally.

WHAT IT DOES AUTOMATICALLY (same logic as proxy.py)
  * FAKE nick detection: tab name with no Pika profile (stats API 404)   -> [DENICK] nick FAKE
  * Real name via scoreboard team leak (team "=<nick>A" lists the real name), team-NAME leak,
    or timing match (2s window, 4s if only one fake nick); leaked name must be a real Pika
    profile with a nick-capable rank                                     -> [DENICK] nick = real
  * Tab list shows:  nick » real   /   nick » FAKE
  * Lobby (>17 in tab): nick-capable staff in team data but not in tab   -> [VANISH] name (rank) has vanished!
  * Spectators (gamemode 3, or spectator teams, not players of this game) -> chat alert + "Spectators:" in tab footer,
    "1st Person" when the spectator is on top of you
  * [CLEAN] lines with level / rank / clan for every normal player
  * Resets on server transfer / respawn like the proxy

COMMANDS
  /denick              help
  /denick list         all results so far
  /denick <name>       status of one player + Pika profile info
  /denick auto         chat alerts on/off        /denick tab    tab rewrite on/off
  /denick clean        [CLEAN] lines on/off      /denick debug  show why leaks were accepted/ignored
  /denick target <name|uuid|off>   proxy's reveal_uuid: flag the tab entry whose UUID matches
  /denick clear        reset                     /denick dump   raw state to logs/latest.log

NOT PORTED because they are disabled in proxy.py itself: invisible-player warning, sidebar scoreboard,
entity-ID correlation.
