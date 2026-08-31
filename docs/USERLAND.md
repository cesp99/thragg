# The Linux userland

The terminal in Seeker IDE can run a real **Debian**, with `apt` and the
tens of thousands of packages that come with it. This page explains what
that is, what it can and cannot do, and where things live.

Every build has it — see [BUILDING.md](BUILDING.md) for what that costs.

## Installing it

Open the terminal (`Ctrl+`` `, or `❯_` in the status bar). If no userland is
installed, a bar offers one: **Install Debian** — about 30 MB to download,
roughly 100 MB on disk once unpacked. The shell you already have keeps
working while it downloads.

When it finishes, the session restarts inside Debian and your prompt lands
in `/projects/<your project>`.

## Using it

It is an ordinary Debian:

```sh
apt update
apt install git python3 build-essential
```

You are `root` inside the userland, which is why `apt` works without
`sudo`. That root is confined to the userland: nothing outside your app's
own storage can be touched, and the rest of the phone is unaffected.

## Git credentials

Clone, fetch, pull and push run git inside the userland, and a remote that
wants a credential — a private HTTPS repository, an SSH key with a passphrase,
a host ssh has never connected to — asks for it in a dialog, the same way
Zed's askpass modal does on a desktop. The dialog is titled with the command
(`git clone`, `git push origin`), shows git's or ssh's own question, masks a
password or passphrase (an eye button shows it), and takes `Enter`/**OK** or
`Esc`/**Cancel**; cancelling makes git give up with its own message.

Use a personal access token where a forge asks for a password: GitHub,
GitLab and Bitbucket all refuse account passwords over HTTPS.

What is remembered, and where:

- Your **username** for a host is kept until the app closes, so a second
  push to the same host asks for the token alone. If it is refused, the next
  prompt shows it pre-filled to correct.
- A **password or passphrase** is kept only if you tick **Remember this
  password for this session** — and dropped again the moment the remote
  refuses it.
- Everything lives in the app's memory and nowhere else. Nothing is written
  to disk or logged, and it is all gone when the app closes.
- ssh's host-key "yes" is never kept by the app: ssh writes it to the
  userland's `~/.ssh/known_hosts` itself, as it would anywhere.

Git's own `credential.helper=cache` is also switched on for these commands
where the userland's git ships it — but every one of them runs in its own
short-lived proot, and the cache daemon dies with it, so on this device the
session memory above is what actually carries a token from one push to the
next. For a memory that survives restarts, set up an SSH key in the
userland's `~/.ssh` (`ssh-keygen` in the terminal) and use an `ssh://` or
`git@` remote; a key without a passphrase never prompts at all.

## Where your files are

| Inside the userland | What it is |
|---|---|
| `/projects` | all your Seeker IDE projects |
| `/projects/<name>` | one project — the same files the editor has open |
| `/root` | the userland's home directory, with your `.bashrc` |
| `/tmp`, `/etc`, `/usr` … | ordinary Debian |

The project directory is the important one: the terminal and the editor are
looking at the same files, so a `git clone` or a code generator run in the
shell shows up in the project panel immediately. (A clone from the shell
prompts *in the shell*; the dialog above serves the project picker's clone
and the git panel's commands.)

## How it works, briefly

Android does not allow an app to run a program that was downloaded rather
than installed — which would make `apt` impossible — unless the app targets
an older SDK. Seeker IDE does, which is also why it cannot be distributed
on Google Play.

Debian's own binaries expect to live at `/usr/bin`, `/etc` and so on. They
are actually inside the app's private storage, so **proot** sits in between,
translating those paths for every process it starts. No root, no
virtualisation: proot uses `ptrace` to rewrite what the guest sees.

## What it cannot do

- **Systemd, services, containers.** There is no init system and no
  privileged operations; `systemctl` and `docker` will not work.
- **Anything needing real root**, such as mounting filesystems or raw
  network sockets. `ping` may not work; `curl` and `git` are fine.
- **Speed.** proot intercepts system calls, so heavy compilation is slower
  than the same work on a Linux machine. Ordinary shell work feels normal.
- **Background survival is now handled, within limits.** While a session
  is running the app holds a foreground service, which keeps Android from
  reaping your build when you switch away. Swiping the app out of Recents
  no longer kills sessions. What it cannot override: the device-wide cap
  on background child processes, and aggressive vendor battery managers.

  That service normally shows a notification, with a **Stop all** action
  that ends every session. You may have to turn it on yourself, in
  Android's app settings for Seeker IDE: the app targets an old API level
  so that the userland can run at all, and Android will not show the
  notification permission prompt to an app that does. Until you allow
  notifications, the service protects your sessions without showing
  anything.

## Removing it

Deleting the userland frees its disk space and loses everything you
installed into it — your projects are not part of it and are untouched.
