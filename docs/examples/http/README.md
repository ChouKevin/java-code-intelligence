# HTTP examples

此目錄保存可由 JetBrains HTTP Client 等工具直接執行的 API 使用範例，不屬於自動化 UAT suite

執行前：

1. 啟動 Java Code Intelligence service
2. 透過 `SEMANTIC_API_TOKEN` 環境變數提供 API token，不要把實際 token 寫入文件
3. 替換範例開頭的 repository、source type、method 與 symbol 變數
4. 先查詢 repository status，將回傳的 `currentRevision` 固定為整段查詢使用的 revision

每份範例應保留完整 follow-up request，讓使用者或 Agent 能直接執行服務回傳的下一步查詢，而不必自行重建 identity 或 revision
