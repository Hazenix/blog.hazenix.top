package top.hazenix.test;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import top.hazenix.entity.Article;
import top.hazenix.entity.MomentLike;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentMapperSqlTest {

    @Test
    void pageMoments_filtersByTagNameInDatabase() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", null);
        params.put("status", 0);
        params.put("momentCategoryId", 9);
        params.put("tagName", "Java");

        String sql = boundSql("mapper/ArticleMapper.xml",
                "top.hazenix.mapper.ArticleMapper.pageMoments", params);

        assertTrue(sql.contains("from article_tags"));
        assertTrue(sql.contains("where tags_name = ?"));
    }

    @Test
    void pageMoments_withoutTagName_doesNotAddTagSubquery() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", null);
        params.put("status", 0);
        params.put("momentCategoryId", 9);
        params.put("tagName", null);

        String sql = boundSql("mapper/ArticleMapper.xml",
                "top.hazenix.mapper.ArticleMapper.pageMoments", params);

        assertFalse(sql.contains("from article_tags"));
    }

    @Test
    void getMomentTagNames_excludesTagsUsedOnlyByDraftMoments() throws IOException {
        String sql = boundSql("mapper/ArticleTagsMapper.xml",
                "top.hazenix.mapper.ArticleTagsMapper.getMomentTagNames",
                Map.of("momentCategoryId", 9));

        assertTrue(sql.contains("a.status = 0"));
    }

    @Test
    void updateMoment_guardsWriteByMomentCategory() throws IOException {
        Article article = Article.builder()
                .id(12L)
                .title(null)
                .content("正文")
                .images("[]")
                .status(1)
                .build();
        String sql = boundSql("mapper/ArticleMapper.xml",
                "top.hazenix.mapper.ArticleMapper.updateMoment",
                Map.of("article", article, "momentCategoryId", 9));

        assertTrue(sql.contains("where id = ? and category_id = ?"));
    }

    @Test
    void incrementMomentCounters_requirePublishedMomentCategory() throws IOException {
        Map<String, Object> params = Map.of(
                "id", 12L,
                "momentCategoryId", 9,
                "publishedStatus", 0
        );

        String viewSql = boundSql("mapper/ArticleMapper.xml",
                "top.hazenix.mapper.ArticleMapper.incrementPublishedMomentViewCount", params);
        String likeSql = boundSql("mapper/ArticleMapper.xml",
                "top.hazenix.mapper.ArticleMapper.incrementPublishedMomentLikeCount", params);

        assertTrue(viewSql.contains("where id = ? and category_id = ? and status = ?"));
        assertTrue(likeSql.contains("where id = ? and category_id = ? and status = ?"));
    }

    @Test
    void selectMomentArticlesForUpdate_locksAllRequestedRows() throws IOException {
        String sql = boundSql("mapper/ArticleMapper.xml",
                "top.hazenix.mapper.ArticleMapper.selectArticlesForUpdate",
                Map.of("ids", List.of(12L, 13L)));

        assertTrue(sql.contains("where id in ( ? , ? ) order by id for update"));
    }

    @Test
    void insertMomentLike_ignoresConcurrentDuplicate() throws IOException {
        MomentLike like = MomentLike.builder()
                .articleId(12L)
                .ip("127.0.0.1")
                .build();
        String sql = boundSql("mapper/MomentLikeMapper.xml",
                "top.hazenix.mapper.MomentLikeMapper.insertIfAbsent", like);

        assertTrue(sql.contains("on conflict (article_id, ip) do nothing"));
    }

    private String boundSql(String resource, String statementId, Object params) throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            XMLMapperBuilder parser = new XMLMapperBuilder(
                    input,
                    configuration,
                    resource,
                    configuration.getSqlFragments()
            );
            parser.parse();
        }
        MappedStatement statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(params);
        return boundSql.getSql().replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
