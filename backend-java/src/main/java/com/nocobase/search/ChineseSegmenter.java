package com.nocobase.search;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 中文分词器 — 基于纯 Java 的 jieba，用于提升中文全文检索的召回与精度。
 *
 * <p><b>为什么不用数据库扩展</b>：Alpine 镜像无 gcc / make / git，
 * 无法编译 {@code zhparser} / {@code pg_jieba} / {@code pg_bigm}，
 * 因此分词在<b>应用层</b>完成，不依赖任何 PostgreSQL 扩展。
 *
 * <p><b>用途</b>：查询串先分词，再逐词检索（见 {@code WikiSearchService}）。
 * 例如「项目管理」→ [项目, 管理]，比整串 {@code ILIKE '%项目管理%'} 能命中
 * 更多相关文档（如正文写「项目的管理工作」）。
 */
@Service
public class ChineseSegmenter {

    /** jieba 官方实现的分词器。 */
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * 对文本分词（检索用途）。
     *
     * <p><b>为什么用 INDEX 而非 SEARCH 模式</b>（实测结论，非推测）：
     * <pre>
     *   输入「项目管理」
     *   SEARCH 模式 → [项目管理]            ← 整词，切不出子词
     *   INDEX  模式 → [项目, 管理, 项目管理]  ← 含子词，召回更好
     * </pre>
     * 检索场景要的是「尽可能命中相关文档」，故用 INDEX。
     *
     * <p><b>噪声处理</b>：INDEX 会产生「理工」（来自「管理工作」）这类无意义子词，
     * 故过滤掉长度 &lt; 2 的词（顺带过滤「的」「了」等单字虚词）；
     * 残余噪声由检索侧的「按命中词数排序」稀释（命中词越多越靠前）。
     *
     * @param text 待分词文本（可为 null / 空白）
     * @return 去空白、去重、过滤单字后的词列表；输入为空或无结果时返回空列表（绝不返回 null）
     */
    public List<String> segment(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<SegToken> tokens = segmenter.process(text, JiebaSegmenter.SegMode.INDEX);
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        return tokens.stream()
                // SegToken.word 是 Word 类型，取值必须用 getToken()（实现 CharSequence）
                .map(t -> t.word == null ? null : t.word.getToken())
                .filter(w -> w != null)
                .map(String::trim)
                // 过滤空串与单字（虚词 / INDEX 噪声碎片）
                .filter(w -> w.length() >= 2)
                .distinct()
                .toList();
    }
}