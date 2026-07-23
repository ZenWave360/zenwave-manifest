# Release notes

Before dispatching a release, commit `release-notes.v<VERSION>.md` here (e.g.
`release-notes.v1.0.0.md`) describing that release. The file must mention the
version string and must exist on `main` before the `Release` workflow is run
— it becomes the GitHub release body, and the pipeline refuses to run
without it.
