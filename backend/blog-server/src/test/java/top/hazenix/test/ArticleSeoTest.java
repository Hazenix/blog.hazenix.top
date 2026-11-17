package top.hazenix.test;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.hazenix.controller.user.ArticleController;
import top.hazenix.service.ArticleService;
import top.hazenix.service.PopularArticleService;
import top.hazenix.service.RecommendService;
import top.hazenix.vo.ArticleSlugVO;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArticleSeoTest {
    private final ArticleService articleService = mock(ArticleService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArticleController(
            articleService, mock(PopularArticleService.class), mock(RecommendService.class))).build();

    @Test
    void sitemapUsesUtf8AndKeepsWorkingAddresses() throws Exception {
        ArticleSlugVO article = new ArticleSlugVO(80L, "中文&标题", null);
        when(articleService.getPublishedArticleSlugs()).thenReturn(List.of(
                article, article, new ArticleSlugVO(54L, "", null), new ArticleSlugVO(1L, "", null)));

        mvc.perform(get("/user/articles/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/xml;charset=UTF-8"))
                .andExpect(content().string(containsString("/article/%E4%B8%AD%E6%96%87%26%E6%A0%87%E9%A2%98</loc>")))
                .andExpect(content().string(containsString("/article/54</loc>")))
                .andExpect(content().string(containsString("/messageboard</loc>")))
                .andExpect(content().string(not(containsString("/article/1</loc>"))))
                .andExpect(content().string(not(containsString("/article/</loc>"))));
    }

    @Test
    void pageStatusUsesCurrentPublicationAndPreservesEncodedIdentifiers() throws Exception {
        when(articleService.isPublished(null, "中文?标题")).thenReturn(true);
        when(articleService.isPublished(54L, "54")).thenReturn(true);

        assertPage("/article/%E4%B8%AD%E6%96%87%3F%E6%A0%87%E9%A2%98?source=feed", 204);
        assertPage("/article/54", 204);
        assertPage("/article/unpublished", 403);
        assertPage("/article/", 403);
    }

    private void assertPage(String uri, int expectedStatus) throws Exception {
        mvc.perform(get("/user/articles/page-status").header("X-Article-URI", uri))
                .andExpect(status().is(expectedStatus));
    }
}
