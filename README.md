# Embabel JBang catalog

Run Embabel with [JBang](https://www.jbang.dev). No installer and no Docker.

## Embabel for Spring

> **Experimental.** New and still changing.

```bash
jbang spring@embabel
```

This starts the Embabel appliance as a plain Java process on your machine, with the realms that
answer "which change broke production": a failure in a Java service's logs traced to the line, the
commit, who wrote it and whether a coding agent helped, and the pull request. Nothing is installed in
your application.

On first run it:

- downloads the appliance jar (about 580 MB, once) and checks its digest;
- makes a data folder, `~/.embabel/spring`, with one user and one MCP token;
- fetches the realms listed in [world-spring](https://github.com/embabel-worlds/world-spring);
- prints the console address, the MCP address, the token, and the command that adds it to Claude Code.

It listens on `127.0.0.1:7042` only.

| Option | Environment variable | Default |
|---|---|---|
| `--port <n>` | `EMBABEL_PORT` | `7042` |
| `--data <folder>` | `EMBABEL_DATA_DIR` | `~/.embabel/spring` |

For private repositories, set `GH_TOKEN` before starting.

### What you need

JBang. It fetches Java 25 itself if you do not have it. About 2 GB of memory.

### Your own realms

Your world is in `<data>/embabel/assistant/users/dev/default`. Add realms to its
`config/realms.yml`; they load beside the Spring ones.
