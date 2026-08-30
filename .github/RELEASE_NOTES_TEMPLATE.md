# Release notes template

<!--
How to use. Two ways, same single source of truth:

  1. Auto (recommended): tag a release and the GitHub Actions workflow
     `.github/workflows/release.yml` creates a *draft* release whose body
     is: the "Which APK" section below (loaded from
     RELEASE_NOTES_PART_APK.md, present in every release even with zero
     PRs because it is unconditional) + the changelog, when any.
     Then, on the draft's page: ✏️ edit if you like, 📎 attach the six
     manually-signed APKs, 🚀 Publish. Push the tag with
     `git push origin --tags`.

  2. Manual: the workflow's `assemble` step prints the exact path of a
     pre-assembled `RELEASE_NOTES.md`; feed that file to
     `gh release create --notes-file RELEASE_NOTES.md --draft`.

The template is intentionally one file: paste/assemble it and the
section never has to be remembered — PR count (even 0) cannot drop it.
-->

{apk-section}

## ✨ What's new

<!--
Filled from Issue/PR history since the previous tag — merged PRs by
label via .github/release.yml ("Generate release notes"), or commit
subjects when there are none. When blank, this heading is the whole
section and that is fine.
-->