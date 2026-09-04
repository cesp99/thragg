//! An app-private trash, standing in for the `trash` crate.
//!
//! Zed's `fs` crate deletes through `trash-rs`, whose fork has no Android
//! backend (`cannot find module platform`). Android has no system trash
//! either, so there is nothing to bind to: the honest implementation is a
//! trash directory the app owns.
//!
//! Only the four items `fs` actually uses are provided — `TrashItem`,
//! `Error`, [`delete_with_info`] and [`restore_all`] — plus [`set_root`],
//! which the engine calls once at startup with the app's private files
//! directory. Semantics match the macOS/Windows arms of `trash-rs`: an item's
//! `id` is the full path it now occupies inside the trash.
//!
//! Entries are moved, never copied, so trashing is atomic and cheap as long as
//! the trash root sits on the same filesystem as the project — which is why
//! the root belongs under the app's data directory rather than in the cache.
//! A cross-filesystem move falls back to copy + remove.

use std::ffi::OsString;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

/// A trashed file or directory, retaining what is needed to restore it.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct TrashItem {
    /// Full path to the entry inside the trash directory.
    pub id: OsString,
    /// File name at the time of trashing, including extension.
    pub name: OsString,
    /// Absolute path of the parent directory at the time of trashing.
    pub original_parent: PathBuf,
    /// Seconds since the Unix epoch.
    pub time_deleted: i64,
}

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("unknown error ({description})")]
    Unknown { description: String },
    #[error("restore collision at {}", path.display())]
    RestoreCollision {
        path: PathBuf,
        remaining_items: Vec<TrashItem>,
    },
}

impl Error {
    fn unknown(description: impl std::fmt::Display) -> Self {
        Error::Unknown {
            description: description.to_string(),
        }
    }
}

fn root_slot() -> &'static RwLock<Option<PathBuf>> {
    static ROOT: OnceLock<RwLock<Option<PathBuf>>> = OnceLock::new();
    ROOT.get_or_init(|| RwLock::new(None))
}

/// Point the trash at `path` (created on first use). Callers should pass an
/// app-private location on the same filesystem as the projects they edit;
/// without this the trash falls back to the system temp directory, which the
/// OS may clear at any time.
pub fn set_root(path: impl Into<PathBuf>) {
    *root_slot().write().unwrap() = Some(path.into());
}

/// The configured trash root, or a temp-dir fallback.
pub fn root() -> PathBuf {
    root_slot()
        .read()
        .unwrap()
        .clone()
        .unwrap_or_else(|| std::env::temp_dir().join("thragg-trash"))
}

/// Move `path` into the trash, returning the entry needed to restore it.
pub fn delete_with_info(path: impl AsRef<Path>) -> Result<TrashItem, Error> {
    let path = path.as_ref();
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().map_err(Error::unknown)?.join(path)
    };
    let name = absolute
        .file_name()
        .ok_or_else(|| Error::unknown(format!("{} has no file name", absolute.display())))?
        .to_owned();
    let original_parent = absolute
        .parent()
        .ok_or_else(|| Error::unknown(format!("{} has no parent", absolute.display())))?
        .to_path_buf();

    // One directory per trashed entry keeps names intact (restore needs them)
    // and makes collisions impossible without stat-ing the trash.
    let slot = root().join(unique_slot());
    fs::create_dir_all(&slot).map_err(Error::unknown)?;
    let destination = slot.join(&name);
    move_entry(&absolute, &destination).map_err(Error::unknown)?;

    Ok(TrashItem {
        id: destination.into_os_string(),
        name,
        original_parent,
        time_deleted: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0),
    })
}

/// Move trashed entries back to where they came from. Stops at the first
/// collision, reporting the items that were not restored.
pub fn restore_all(items: impl IntoIterator<Item = TrashItem>) -> Result<(), Error> {
    let items: Vec<TrashItem> = items.into_iter().collect();
    for (index, item) in items.iter().enumerate() {
        let destination = item.original_parent.join(&item.name);
        if destination.exists() {
            return Err(Error::RestoreCollision {
                path: destination,
                remaining_items: items[index..].to_vec(),
            });
        }
        let source = PathBuf::from(&item.id);
        fs::create_dir_all(&item.original_parent).map_err(Error::unknown)?;
        move_entry(&source, &destination).map_err(Error::unknown)?;
        // The per-entry slot directory is now empty; leaving it would grow the
        // trash without bound.
        if let Some(slot) = source.parent() {
            let _ = fs::remove_dir(slot);
        }
    }
    Ok(())
}

fn unique_slot() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let count = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{nanos:x}-{count:x}")
}

/// `rename` when both sides share a filesystem, copy + remove otherwise.
fn move_entry(source: &Path, destination: &Path) -> io::Result<()> {
    match fs::rename(source, destination) {
        Ok(()) => Ok(()),
        Err(err) if err.raw_os_error() == Some(libc_exdev()) => {
            copy_recursive(source, destination)?;
            if source.is_dir() {
                fs::remove_dir_all(source)
            } else {
                fs::remove_file(source)
            }
        }
        Err(err) => Err(err),
    }
}

/// `EXDEV` — cross-device link. The value is 18 on Linux/Android; other
/// platforms only matter for host-side tests, where the fallback simply never
/// triggers.
fn libc_exdev() -> i32 {
    18
}

fn copy_recursive(source: &Path, destination: &Path) -> io::Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.is_dir() {
        fs::create_dir_all(destination)?;
        for entry in fs::read_dir(source)? {
            let entry = entry?;
            copy_recursive(&entry.path(), &destination.join(entry.file_name()))?;
        }
        Ok(())
    } else {
        fs::copy(source, destination).map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The trash root is process-global, so tests that set it take turns.
    fn serialized() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
        LOCK.lock().unwrap_or_else(|err| err.into_inner())
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("thragg-trash-test-{name}-{}", unique_slot()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn trash_and_restore_roundtrip() {
        let _guard = serialized();
        let base = temp_dir("roundtrip");
        set_root(base.join("trash"));
        let project = base.join("project");
        fs::create_dir_all(&project).unwrap();
        let file = project.join("notes.txt");
        fs::write(&file, "hello").unwrap();

        let item = delete_with_info(&file).unwrap();
        assert!(!file.exists());
        assert_eq!(item.name, OsString::from("notes.txt"));
        assert_eq!(item.original_parent, project);
        assert_eq!(
            fs::read_to_string(PathBuf::from(&item.id)).unwrap(),
            "hello"
        );

        restore_all([item]).unwrap();
        assert_eq!(fs::read_to_string(&file).unwrap(), "hello");
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn restore_reports_collisions() {
        let _guard = serialized();
        let base = temp_dir("collision");
        set_root(base.join("trash"));
        let project = base.join("project");
        fs::create_dir_all(&project).unwrap();
        let file = project.join("a.txt");
        fs::write(&file, "first").unwrap();

        let item = delete_with_info(&file).unwrap();
        fs::write(&file, "second").unwrap();

        match restore_all([item]) {
            Err(Error::RestoreCollision {
                path,
                remaining_items,
            }) => {
                assert_eq!(path, file);
                assert_eq!(remaining_items.len(), 1);
            }
            other => panic!("expected a collision, got {other:?}"),
        }
        // The original was left untouched.
        assert_eq!(fs::read_to_string(&file).unwrap(), "second");
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn trashes_directories_whole() {
        let _guard = serialized();
        let base = temp_dir("dirs");
        set_root(base.join("trash"));
        let dir = base.join("project/src");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("lib.rs"), "fn main() {}").unwrap();

        let item = delete_with_info(&dir).unwrap();
        assert!(!dir.exists());
        assert!(PathBuf::from(&item.id).join("lib.rs").exists());
        fs::remove_dir_all(base).unwrap();
    }
}
