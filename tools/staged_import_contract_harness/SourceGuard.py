from pathlib import Path
root = Path(__file__).resolve().parents[2]
store = (root / "app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceStore.kt").read_text()
activity = (root / "app/src/main/java/pl/aimasterpro/app/MainActivity.kt").read_text()
assert "fun importZip(input: InputStream, archiveName: String" in store
assert "val records = extract(input, staging)" in store
assert "verifyCurrentWorkspaceInternal()" in store
assert "replaceCurrent(staging, stagedManifest)" in store
call = "ProjectWorkspaceStore(File(filesDir, \"project_workspace\")).importZip(it, archiveName)"
assert call in activity
call_index = activity.index(call)
start = activity.rfind("ProjectWorkspaceOperationLock.withLock {", max(0, call_index - 1200), call_index)
end = activity.index("HostActiveChangeSetStore.invalidate(applicationContext)", call_index)
assert start >= 0 and end > call_index, "import and evidence invalidation must remain in one workspace critical section"
section = activity[start:end]
assert call in section
print("PASS source guard: monolithic import and evidence invalidation remain serialized; no premature lock-scope regression")
