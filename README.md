# File Investigator — Android Studio project

A Kotlin starter application for investigating files stored in **shared/external storage** on an
Android phone. It is **not** an installed APK and has not been compiled or device-tested in this
environment.

## Features

- Requests image/video/audio permissions using Android's runtime permission UI (including
  selected-media access on modern Android).
- Indexes accessible pictures, video, and audio through MediaStore.
- Adds additional folders through Android's system folder chooser; scans children recursively, up to
  20,000 entries per granted folder.
- Lists file name, size, date, MIME type, location, and source app when Android exposes one.
  Sorting: size ascending/descending, newest/oldest, name, source. Filters: type, source, date,
  free-text and AI image labels.
- On-device ML Kit image labeling for selected images (no image upload for classification).
  Confidence threshold 0.50; labels are approximate and exist only in memory until the app closes.
- Multi-select; explicit confirmation before irreversible deletion. For MediaStore items, Android's
  system deletion confirmation is used on Android 11+.
- Dropbox OAuth 2.0 PKCE; upload selected files into a specified Dropbox directory using
  non-overwriting unique file names. Only after size-confirmed upload does it **offer** a separate
  deletion approval. Original files are kept by default.

## Build and run

1. Open the `PhoneFileOrganizer` folder in Android Studio and let it import the Gradle project. Use
   JDK 17 and Android SDK Platform 35. The project specifies Gradle 8.11.1 but this source archive
   **does not contain** a Gradle Wrapper binary: Android Studio can use a locally installed Gradle,
   or from a terminal with Gradle installed run `gradle wrapper --gradle-version 8.11.1` once to
   generate `gradlew` and wrapper jar.
2. For Dropbox support create a Dropbox API app at https://www.dropbox.com/developers/apps. Choose
   scoped access, preferably App Folder, and grant `files.content.write`. Copy its App Key.
3. Add to `local.properties` (beside `settings.gradle.kts`):

   ```properties
   sdk.dir=/your/android/sdk/path
   DROPBOX_APP_KEY=YOUR_DROPBOX_APP_KEY
   ```

4. In the Dropbox developer console configure the app's permissions and Android URI scheme. The SDK
   handles its PKCE sign-in through `db-YOUR_DROPBOX_APP_KEY`; no client secret should be embedded
   in the app.
5. Sync Gradle, build, install on an Android phone (Android 8+), and grant media / folder access
   from the app buttons.

## Permissions and Android restrictions

- **All files on the phone is not possible for an ordinary Android application.** MediaStore covers
  media the app can access, while Android's SAF covers user-granted folders and documents. Other
  apps' private `/Android/data` and `/Android/obb` directories and many protected locations remain
  inaccessible. On Android 11+, the system picker also disallows choosing root storage and Downloads
  as a whole folder tree. For Download documents, add a user-controlled file picker as a future
  enhancement, or select an allowed subfolder if the picker permits it.
- `OWNER_PACKAGE_NAME` may be unavailable, blank, or privacy-filtered, and does not prove which
  application originally created a file. Files from user-granted folders are marked as creator
  unknown.
- Folder scanning is synchronous within an IO coroutine; for very large inventories a production
  implementation should use pagination/background jobs and a database rather than holding records
  only in memory.
- The list renders at most 250 matching files at once; narrow the filters to access more. The
  underlying scan may index more.
- Dropbox upload currently verifies reported remote byte count, **not a cryptographic hash**. Before
  considering full backup-grade moves, implement integrity verification, durable job state, retries,
  network handling, and support for Dropbox large-file upload sessions (the simple upload endpoint
  has limits).
- The Dropbox login credential is **only kept in memory** for this initial sample. Restarting the
  app requires reauthorization. A production app should use encrypted, lifecycle-safe credential
  storage and token refresh.
- There is no unattended or automatic deletion. Destructive actions require explicit user approvals.

## Architecture

- `FileScanner.kt`: Android shared-media index and SAF folder traversal
- `FileItem.kt`: file metadata model
- `MainActivity.kt`: UI, runtime permissions, filters, local AI labels, Dropbox OAuth/upload,
  deletion workflow

## Roadmap before a Play Store release

Add Room for durable indexing and label cache, paging, progress/cancel operations, WorkManager
uploads, user-selectable individual non-media documents, accessible settings/navigation screens,
preview thumbnails, hash validation, privacy policy, testing against Android 13–16, backup-safe
resumable Dropbox transfers, and Play policy evaluation for storage permissions.
