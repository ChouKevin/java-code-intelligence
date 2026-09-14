package com.java.semantic.model.git;

/** Exact file-content availability captured while reading a Git tree without host-file fallback. */
public enum GitFileContentStatus {
    TEXT, BINARY, UNSUPPORTED_ENCODING, TOO_LARGE, SYMLINK, SUBMODULE, LFS_POINTER, UNSUPPORTED_PATH
}
