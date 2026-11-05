# NOCTRA — Android music app

NOCTRA is an early Android music-player prototype with a black/white/gray interface, search, streaming playback and an open-license music catalog sourced from Internet Archive.

## Important about the catalog

This is **not** a commercial streaming catalog. The app filters for recordings marked with CC BY, CC BY-SA, CC0 or Public Domain Mark licenses. Availability and metadata depend on Internet Archive. Commercial recordings by popular artists cannot be included unless the rights holders license them.

## Build an installable APK using GitHub Actions (no Android Studio needed)

The build runs on GitHub's hosted Android build machine. You need a free GitHub account. The result is a file named `app-debug.apk` that can be downloaded to an Android phone and installed.

### Easiest upload route (one ZIP + one workflow file)

1. Open https://github.com/new and create a repository named `NOCTRA`. You can leave it Public or choose Private. Do not add a README, .gitignore or license.
2. Open the new repository and choose **Add file → Upload files**.
3. Upload this project ZIP (`NOCTRA-Android-Project-Monochrome.zip`) as a file at the repository root. Commit the upload to the `main` branch.
4. In the repository choose **Add file → Create new file**.
5. In the filename field enter exactly `.github/workflows/android.yml`.
6. Open `GITHUB-WORKFLOW-COPY.txt` from the project pack, copy all its contents into the GitHub editor, and commit the new file to `main`.
7. Open the repository's **Actions** tab. The workflow **Build NOCTRA APK from ZIP** should start. If it does not, open it and choose **Run workflow**.
8. When the run has a green check, open it, scroll to **Artifacts**, and download `NOCTRA-debug-apk`.
9. Extract the downloaded artifact ZIP. Inside is `app-debug.apk`. Open that APK on your Android phone and approve installation from that source if Android asks.

### Alternative

If you upload the extracted project files (rather than the ZIP) into the repository, the workflow already located at `.github/workflows/android.yml` in the source project can build it directly. The one-ZIP route above is usually simpler from a phone.

## Current limitations

- This is an early prototype, not a finished Yandex Music replacement.
- Favorites currently live only in app memory and are not synced or permanently stored.
- Playlist management, artist pages, listening history, offline downloads, accounts and recommendations are not implemented yet.
- The app requires an internet connection to search and stream the open catalog.
- A debug APK is unsigned for Play Store distribution; it is suitable for testing and direct installation.
