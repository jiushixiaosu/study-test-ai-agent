package com.fanzhuo.quickstart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 带 JSON 文件持久化能力的向量库（继承 SimpleVectorStore）。
 * <p>
 * 背景：SimpleVectorStore 内部是 ConcurrentHashMap，文档只在内存，重启即丢，
 * 每次重启都要重新调 Embedding API 向量化，既慢又费钱。
 * <p>
 * 采用「继承」而非「装饰器」的原因：
 * SimpleVectorStore 的底层存储字段 store 是 protected，且没有公开的 size()/isEmpty() 方法。
 * 继承后可直接访问 store，从而补上「容量查询」与「清空重建」能力——
 * 这是 /api/rag/count 接口与知识库变更后重建所必需的。
 * <p>
 * 写操作通过重写模板方法 doAdd / doDelete 拦截，自动落盘，
 * 因此无论谁调用 add()/delete()（知识库初始化、HTTP 接口）都会持久化。
 */
public class PersistentVectorStore extends SimpleVectorStore {

    private static final Logger log = LoggerFactory.getLogger(PersistentVectorStore.class);

    private final File file;

    private PersistentVectorStore(SimpleVectorStoreBuilder builder, File file) {
        super(builder);
        this.file = file;
    }

    public static PersistentVectorStore create(EmbeddingModel embeddingModel, File file) {
        return new PersistentVectorStore(SimpleVectorStore.builder(embeddingModel), file);
    }

    // ===== 写操作：调父类后自动落盘 =====

    @Override
    public void doAdd(List<Document> documents) {
        super.doAdd(documents);
        persist();
    }

    @Override
    public void doDelete(List<String> idList) {
        super.doDelete(idList);
        persist();
    }

    @Override
    public void doDelete(Filter.Expression filterExpression) {
        super.doDelete(filterExpression);
        persist();
    }

    // ===== 容量查询与清空（依赖 protected 的 store 字段） =====

    /** 当前库中文档块数量。 */
    public int size() {
        return this.store.size();
    }

    public boolean isEmpty() {
        return this.store.isEmpty();
    }

    /** 清空全部向量并落盘（用于知识库变更后的重建）。 */
    public void clearAll() {
        int before = this.store.size();
        this.store.clear();
        persist();
        log.info("向量库已清空，移除 {} 个分块", before);
    }

    // ===== 持久化控制 =====

    /** 启动时调用：若磁盘快照存在则恢复到内存。 */
    public void loadIfExists() {
        if (file == null) {
            return;
        }
        if (!file.exists()) {
            log.info("向量库快照不存在，将以空库启动：{}", file.getAbsolutePath());
            return;
        }
        try {
            load(file);
            log.info("已从快照恢复向量库：{}，共 {} 个分块", file.getAbsolutePath(), size());
        } catch (Exception e) {
            log.error("恢复向量库失败（将以空库启动）：{}", file.getAbsolutePath(), e);
        }
    }

    /** 将内存中的向量库写入 JSON 快照。写操作后自动调用。 */
    private void persist() {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("无法创建向量库目录：{}", parent.getAbsolutePath());
            }
            save(file);
            log.debug("向量库已落盘：{}（{} 个分块）", file.getAbsolutePath(), size());
        } catch (Exception e) {
            log.error("保存向量库失败：{}", file.getAbsolutePath(), e);
        }
    }
}
