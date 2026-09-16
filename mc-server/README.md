# mc-server — servidor Paper de desenvolvimento

Servidor Paper 1.21.1 (build 133) para testar o plugin `FlywireBeePlugin` (L6).
Roda nativo no Windows — só o simulador é containerizado (AD-09).

Todo o conteúdo gerado (mundo, logs, cache, o próprio `.jar` do Paper) é
ignorado pelo git — cada máquina gera o seu. Só `server.properties` e
`eula.txt` ficam versionados, porque guardam decisões de configuração, não
dado gerado.

## Configuração já feita

- `online-mode=false` — conta do TLauncher é offline/pirata; com
  `online-mode=true` o servidor rejeitaria a conexão na autenticação.
- `enforce-secure-profile=false` — exigido junto com `online-mode=false`,
  senão o cliente é desconectado por falta de sessão de chat assinada.
- `eula=true` em `eula.txt` — aceite do [EULA da Mojang](https://aka.ms/MinecraftEULA),
  obrigatório para rodar qualquer servidor.

## Como rodar

```
cd mc-server
java -jar paper-1.21.1-133.jar --nogui
```

Primeira execução baixa/aplica os patches do Paper sobre o jar vanilla
(gera `versions/`, `cache/`, `libraries/`) — só na primeira vez.

Depois, conectar no TLauncher em `localhost` (porta padrão 25565, já
configurada em `server.properties`).

## Plugin

O `.jar` compilado de `plugin/` (ver `plugin/README.md`) deve ir em
`mc-server/plugins/` antes de iniciar o servidor. O log do servidor mostra se
o plugin carregou e se conseguiu conectar no simulador
(`FlywireBeePlugin#onEnable`).

**Ordem de inicialização importa:** suba o simulador (`docker compose up` em
`sim/`) antes do servidor Minecraft — o plugin tenta conectar no `onEnable` e
só loga um warning se falhar (não trava o servidor), mas sem o simulador de
pé ele não vai ter nada pra conversar.
