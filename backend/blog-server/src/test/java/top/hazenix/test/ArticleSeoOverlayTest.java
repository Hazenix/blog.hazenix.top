package top.hazenix.test;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.hazenix.controller.user.ArticleSeoOverlayController;
import top.hazenix.service.ArticleService;
import top.hazenix.vo.ArticleDetailVO;
import top.hazenix.vo.ArticleSlugVO;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArticleSeoOverlayTest {
    @Test
    void publishesCanonicalUrlsAndEscapesFallbackHtml() throws Exception {
        var service = mock(ArticleService.class);
        var article = new ArticleDetailVO();
        article.setTitle("中文 <script>");
        article.setContent("文章正文 <script>");
        when(service.getArticleDetail(80L)).thenReturn(article);
        when(service.getPublishedArticleSlugs()).thenReturn(List.of(new ArticleSlugVO(80L, "中文", null),
                new ArticleSlugVO(1L, "", null), new ArticleSlugVO(54L, "", null)));
        var mvc = MockMvcBuilders.standaloneSetup(new ArticleSeoOverlayController(service)).build();
        mvc.perform(get("/user/articles/seo-sitemap"))
                .andExpect(content().contentType("application/xml;charset=UTF-8"))
                .andExpect(content().string(containsString("/article/%E4%B8%AD%E6%96%87")))
                .andExpect(content().string(containsString("/article/54")))
                .andExpect(content().string(not(containsString("/article/1<"))));
        mvc.perform(get("/user/articles/seo-status").header("X-Article-URI", "/article/80?from=index"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Blog-Article-Canonical", "/article/%E4%B8%AD%E6%96%87"));
        mvc.perform(get("/user/articles/seo-status").header("X-Article-URI", "/article/withdrawn"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/user/articles/seo-html").header("X-Article-URI", "/article/80"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("文章正文 &lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>"))));
    }
}
