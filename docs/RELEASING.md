# Making a release

The plugin `.jar` is distributed through **GitHub Releases**, not committed to the repository.

## What a release is

A GitHub Release has three parts:

- **A tag:** a permanent name, such as `v0.1.0`, pinned to one commit, so you can always see exactly which code a version was built from.
- **Release notes:** a title and a description of what changed, shown on the release page.
- **Assets:** files attached to the release. This is where the plugin `.jar` goes. GitHub also adds two "Source code" archives to every release automatically; users can ignore them.

The README links to `https://github.com/Alex870/biglybt-ProtonVPNPort/releases/latest`. That address always opens the newest **published, non-pre-release** release, so the README never needs editing when a new version comes out.

Release states:

| State | Who can see it | Shown as "Latest"? | Use it for |
|---|---|---|---|
| **Draft** | Only you (repo collaborators) | No | Preparing a release; testing the jar before anyone else can download it |
| **Pre-release** | Everyone | No | Optional: inviting testers while normal users stay on the previous version |
| **Published** | Everyone | Yes (the newest one) | A normal release |

## Steps

1. **Set the version.** Change `<version>` in `Eclipse_Project/pom.xml` (e.g. `0.1.1`). That's the only place the version lives; `plugin.properties` takes it from the pom at build time.
2. **Merge to `main`.** Release from `main`, so the tag points at code everyone can see.
3. **Build the jar:**
   ```bash
   mvn -f Eclipse_Project/pom.xml clean package -Dbiglybt.jar="C:\Program Files\BiglyBT\BiglyBT.jar"
   ```
   The result is `Eclipse_Project/target/ProtonVPN_<version>.jar`. The build runs the tests first and stops if any fail.
4. **Test it.** Install the jar in BiglyBT (**Tools -> Plugins -> Install from file**) and check it works.
5. **Create the release on GitHub:**
   1. Go to the repository's **Releases** page and click **Draft a new release**.
   2. **Choose a tag:** type `v<version>` (e.g. `v0.1.1`) and pick **Create new tag on publish**. Target: `main`.
   3. **Release title:** e.g. `0.1.1`.
   4. **Description:** what changed, written for users. Keep the "How to install" line from earlier releases.
   5. **Attach the jar:** drag `ProtonVPN_<version>.jar` into the "Attach binaries" box.
   6. Click **Save draft** if you want to check the page first, or **Publish release** when ready.
6. **Check:** open the README's "Download the latest release" link and confirm it shows the new version with the jar under **Assets**.

To fix a mistake after publishing, edit the release: you can replace the attached jar or change the notes. Don't move or reuse a tag. If the code was wrong, release a new version instead.

## Release notes template

```markdown
## What's new
- ...

## How to install
Download **ProtonVPN_<version>.jar** from the Assets below (not the "Source code" files), then in BiglyBT use
**Tools -> Plugins -> Install from file**. Full instructions: see the README.
```
