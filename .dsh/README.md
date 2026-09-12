# DeepSeek Harness (`dsh`) project config

`dsh` discovers project skills at `<projectRoot>/.dsh/skills`, in two forms only (one level deep):
a bundle `<name>/SKILL.md`, or a flat `<name>.md`. It has no Markdown slash-command surface — a
skill with `user-invocable` (the default) *is* the human command.

**Everything here is a relative symlink. Nothing in this directory is a copy — never edit a link's
content here.** The canonical files live under `.claude/`:

| Link | Canonical file | Claude surface | dsh surface |
|---|---|---|---|
| `skills/<name>` | `.claude/skills/<name>/SKILL.md` | skill | skill + command |
| `skills/re-*.md` | `.claude/commands/re-*.md` | slash command | command only |

The `re-*` files carry `disable-model-invocation: true` so both harnesses treat them the same way
they always have: user-invoked only, absent from the model's skill catalog.

## Adding a skill or command

Author it under `.claude/`, give it YAML frontmatter with kebab-case `name` and a `description`
(both are *required* by dsh — a file missing either is dropped with a warning), then relink:

```sh
for d in .claude/skills/*/;     do n=$(basename "$d"); ln -sfn "../../.claude/skills/$n"   ".dsh/skills/$n"; done
for f in .claude/commands/*.md; do n=$(basename "$f"); ln -sfn "../../.claude/commands/$n" ".dsh/skills/$n"; done
```

## MCP servers — `cordis.patch.yml`

dsh has no auto-discovered project config: its composition root is
`$DSH_HOME/profiles/<profile>/cordis.yml`, and `settings.yaml` has a single user layer with no
project scope. What it does have is `--patch <path>`, repeatable and resolved against the CWD — so
`cordis.patch.yml` here is a committed project overlay that you opt into per launch, from the repo
root:

```sh
dsh --profile cc --patch .dsh/cordis.patch.yml
dsh --profile cc --patch .dsh/cordis.patch.yml --dump-config   # verify the tree, no boot
```

It carries the same two servers as `.mcp.json`, as `@deepseek-ai/dsh-mcp-client` rows. The three
harnesses need three formats, so that one file is a genuine parallel copy — keep it in step with
`.mcp.json` and `opencode.json`. Tool names are identical everywhere (`mcp__<serverName>__<rawName>`),
which is why no skill or command text is harness-specific.

Two dsh-only constraints: transports are `stdio` and `streamable-http` **only** (no SSE), and the
overlay is snapshotted at boot — editing it does not hot-reload the way the profile's own
`cordis.patch.yml` does.

## Not handled here

- **Instructions** — dsh loads `CLAUDE.md` natively (candidates default to `AGENTS.md`, `CLAUDE.md`),
  but it does not interpret the `@.claude/memory/INDEX.md` import, so under dsh that index must be
  read on demand rather than arriving preloaded.
- **Subagents** — `.claude/agents/` has no dsh equivalent; dsh subagents are composed in the profile.
