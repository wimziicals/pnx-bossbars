# Boss Bars

Adds the `/bossbar` command from Minecraft: Java Edition to [PowerNukkitX](https://github.com/PowerNukkitX/PowerNukkitX), including colours, notched styles, rawtext names with live scores, and bars that stay put across dimension changes, rejoins and restarts.

## Features

- **Every Java `/bossbar` subcommand**: `add`, `get`, `list`, `remove` and `set`, with the same feedback messages as vanilla
- **Colours**: `pink`, `blue`, `red`, `green`, `yellow`, `purple`, `white`
- **Notched styles**: `progress`, `notched_6`, `notched_10`, `notched_12`, `notched_20`, rendered by the bundled resource pack
- **Rawtext names**: `text`, `selector`, `score` and `translate` components, resolved separately for each viewer, so `@s` shows each player their own name
- **Live scores**: names with `score` components update automatically as scores change
- **Persistent**: bars are saved to `bossbars.json` and restored after restarts; players get their bars back when they rejoin or change dimension
- **Autocomplete**: bar IDs are suggested in the command bar and update as bars are added or removed

## Installation

1. Download `BossBars.jar` from the latest release.
2. Put it in your server's `plugins` folder.
3. Restart the server.

The resource pack is bundled inside the jar and is required for the Java-style bars and notched styles. Without it, players see Bedrock's default boss bar.

## Commands

| Command                                          | Description                                           |
| ------------------------------------------------ | ----------------------------------------------------- |
| `/bossbar add <id> <name>`                       | Creates a new boss bar                                |
| `/bossbar remove <id>`                           | Removes a boss bar                                    |
| `/bossbar list`                                  | Lists all boss bars                                   |
| `/bossbar get <id> max\|players\|value\|visible` | Shows a property of a boss bar                        |
| `/bossbar set <id> color <color>`                | Sets the colour                                       |
| `/bossbar set <id> max <max>`                    | Sets the maximum value (at least 1)                   |
| `/bossbar set <id> name <name>`                  | Sets the name (plain text or rawtext JSON)            |
| `/bossbar set <id> players [targets]`            | Sets who can see the bar (leave targets out to clear) |
| `/bossbar set <id> style <style>`                | Sets the style                                        |
| `/bossbar set <id> value <value>`                | Sets the current value (at least 0)                   |
| `/bossbar set <id> visible <true\|false>`        | Shows or hides the bar                                |

IDs without a namespace get `minecraft:` added automatically, like vanilla, so `test` becomes `minecraft:test`.

New bars start with a value of `0`, a max of `100`, `white` colour, `progress` style, visible, and no players.

## Rawtext names

Names starting with `{` are read as Bedrock rawtext JSON:

```
/bossbar add welcome {"rawtext":[{"text":"§dWelcome, §f"},{"selector":"@s"},{"text":" §dto the server!"}]}
/bossbar set welcome players @a
```

```
/bossbar add kills {"rawtext":[{"text":"Kills: "},{"score":{"name":"@s","objective":"kills"}}]}
```

- Each player sees the name resolved for themselves.
- Scores refresh about once a second.
- A component that can't be resolved, such as a missing score, shows as empty instead of breaking the whole name.
- Invalid JSON is rejected with an error explaining what went wrong.

## Examples

```
/bossbar add event §6Event starts soon
/bossbar set event color yellow
/bossbar set event style notched_10
/bossbar set event max 60
/bossbar set event value 60
/bossbar set event players @a
```

## Permissions

| Permission                 | Default | Description    |
| -------------------------- | ------- | -------------- |
| `mistvale.command.bossbar` | OP      | Use `/bossbar` |

## Differences from Java Edition

- Only players can be targets. Bedrock boss bars can't be shown to other entities.
- Names use Bedrock rawtext JSON (`{"rawtext":[...]}`) instead of Java text components.

## Building

Requires JDK 25 and Maven.

```
mvn package
```

The built jar is placed in `target/`.
