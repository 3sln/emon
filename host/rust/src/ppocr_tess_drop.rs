// Included in the shared adapter's assembled tess module, where the native
// handle map is private. The short-lived reference historically left these
// handles to process teardown; a shared library must release them on close.
impl Drop for Tess {
    fn drop(&mut self) {
        for (_, handle) in self.apis.drain() {
            unsafe { TessBaseAPIDelete(handle) };
        }
    }
}
