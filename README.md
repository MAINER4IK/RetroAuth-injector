# retroauth-injector

`retroauth-injector` is a Java Agent for legacy Minecraft versions, combining ideas from `authlib-injector`, `Agenta` and `CustomSkinLoader` .

## Features

- Runtime legacy URL interception (agenta-style URL hook).
- Legacy skin/cape endpoint polyfills.
- Legacy `MinecraftResources` endpoint support.
- Compatibility helpers for old auth/game endpoints.

## Build

Requirements:
- JDK 17+
- Gradle

```bash
gradle shadowJar
```

Output:

```text
build/libs/retroauth-injector-<version>.jar
```

## Run

```bash
-javaagent:/path/to/retroauth-injector.jar=https://your-auth-server.example/
```

## Main JVM options

```text
-Dauthlibinjector.agenta.skin.resize=true|false
-Dauthlibinjector.agenta.skin.merge=true|false
-Dauthlibinjector.agenta.skin.cache=true|false
-Dauthlibinjector.agenta.skin.hd=true|false
-Dauthlibinjector.agenta.assets.fml=<url>
-Dauthlibinjector.agenta.save.file=<path>
```

All standard `authlib-injector` options remain available.

## Upstreams

- Agenta: https://github.com/Hanro50/Agenta
- authlib-injector: https://github.com/yushijinhun/authlib-injector
- CustomSkinLoader: https://github.com/xfl03/MCCustomSkinLoader
