//! Cooperative file IO gate shared by guest turns and host file publication.
//! Every filesystem writer must use this gate; it is not a guest sandbox itself.
#[cfg(unix)]
pub(crate) mod unix {
    use anyhow::{Result, ensure};
    use std::{ffi::{c_char, CStr, CString}, fs::File, os::fd::{AsRawFd, FromRawFd}, os::unix::fs::MetadataExt};
    pub struct PodGuestIo { lock: File, pub(crate) held: bool, pub(crate) root: File, pub(crate) staging: File }
    fn opened(fd: i32) -> Result<File> { ensure!(fd >= 0, "guest IO open: {}", std::io::Error::last_os_error()); Ok(unsafe { File::from_raw_fd(fd) }) }
    impl PodGuestIo {
        fn open(root: &CStr) -> Result<Self> {
            ensure!(root.to_bytes().len() > 1 && root.to_bytes().len() <= 4096 && root.to_bytes().first() == Some(&b'/'), "invalid guest IO root");
            let directory = opened(unsafe { libc::open(root.as_ptr(), libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC) })?;
            let metadata = directory.metadata()?;
            ensure!(metadata.uid() == unsafe { libc::geteuid() } && metadata.mode() & 0o022 == 0, "unsafe guest IO root");
            let name = CString::new("sync-save")?;
            if unsafe { libc::mkdirat(directory.as_raw_fd(), name.as_ptr(), 0o700) } != 0 {
                ensure!(std::io::Error::last_os_error().raw_os_error() == Some(libc::EEXIST), "cannot create guest IO directory");
            }
            let private = opened(unsafe { libc::openat(directory.as_raw_fd(), name.as_ptr(), libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC) })?;
            let metadata = private.metadata()?;
            ensure!(metadata.uid() == unsafe { libc::geteuid() } && metadata.mode() & 0o077 == 0, "unsafe guest IO directory");
            let name = CString::new("owner.lock")?;
            let lock = opened(unsafe { libc::openat(private.as_raw_fd(), name.as_ptr(), libc::O_RDWR | libc::O_CREAT | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC, 0o600) })?;
            let metadata = lock.metadata()?;
            ensure!(metadata.is_file() && metadata.uid() == unsafe { libc::geteuid() } && metadata.mode() & 0o077 == 0 && metadata.nlink() == 1, "unsafe guest IO lock");
            private.sync_all()?; directory.sync_all()?;
            Ok(Self { lock, held: false, root: directory, staging: private })
        }
        fn enter(&mut self) -> Result<bool> {
            ensure!(!self.held, "guest IO gate already held");
            loop {
                if unsafe { libc::flock(self.lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0 { self.held = true; return Ok(true); }
                let error = std::io::Error::last_os_error();
                if error.kind() == std::io::ErrorKind::Interrupted { continue; }
                if error.kind() == std::io::ErrorKind::WouldBlock { return Ok(false); }
                return Err(error.into());
            }
        }
    }
    /// # Safety
    /// Existing trusted runtime data directory; valid NUL-terminated string.
    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn pod_guest_io_open(root: *const c_char) -> *mut PodGuestIo {
        if root.is_null() { return std::ptr::null_mut(); }
        match PodGuestIo::open(unsafe { CStr::from_ptr(root) }) {
            Ok(gate) => Box::into_raw(Box::new(gate)),
            Err(error) => { crate::set_error(error.to_string()); std::ptr::null_mut() }
        }
    }
    /// # Safety
    /// Exclusively borrowed live handle. Returns 1 acquired, 0 busy, -1 error.
    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn pod_guest_io_try_enter(gate: *mut PodGuestIo) -> i32 {
        let Some(gate) = (unsafe { gate.as_mut() }) else { return -1; };
        match gate.enter() { Ok(entered) => i32::from(entered), Err(error) => { crate::set_error(error.to_string()); -1 } }
    }
    /// # Safety
    /// Exclusively borrowed live handle; only its owner may release it.
    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn pod_guest_io_leave(gate: *mut PodGuestIo) {
        if let Some(gate) = unsafe { gate.as_mut() } { if gate.held {
            loop {
                if unsafe { libc::flock(gate.lock.as_raw_fd(), libc::LOCK_UN) } == 0 { gate.held = false; break; }
                let error = std::io::Error::last_os_error();
                if error.kind() == std::io::ErrorKind::Interrupted { continue; }
                crate::set_error(error.to_string()); break;
            }
        } }
    }
    /// # Safety
    /// Null or owned live handle, closed exactly once. Close releases any lease.
    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn pod_guest_io_close(gate: *mut PodGuestIo) { if !gate.is_null() { drop(unsafe { Box::from_raw(gate) }); } }
    #[cfg(test)] mod tests {
        use super::*;
        #[test] fn independent_descriptors_exclude_and_close_releases() {
            let root = std::env::temp_dir().join(format!("podjs-guest-gate-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
            std::fs::create_dir(&root).unwrap();
            let path = CString::new(root.to_str().unwrap()).unwrap();
            let mut first = PodGuestIo::open(&path).unwrap(); let mut second = PodGuestIo::open(&path).unwrap();
            assert!(first.enter().unwrap()); assert!(!second.enter().unwrap()); assert!(first.enter().is_err());
            unsafe { pod_guest_io_leave(&mut first); } assert!(second.enter().unwrap()); drop(second);
            assert!(first.enter().unwrap()); drop(first);
            let alias = root.join("alias"); std::fs::hard_link(root.join("sync-save/owner.lock"), &alias).unwrap();
            assert!(PodGuestIo::open(&path).is_err());
            std::fs::remove_dir_all(root).unwrap();
        }
    }
}
#[cfg(unix)] pub use unix::*;
