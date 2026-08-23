package com.java.semantic.semantic.adapter.jdtls;

/** 與 JDT LS java/buildWorkspace 回傳值對應的內部狀態。 */
enum JdtLsBuildWorkspaceStatus {
    FAILED,
    SUCCEED,
    WITH_ERROR,
    CANCELLED
}
