package top.hazenix.controller.user;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;
import top.hazenix.service.ArticleService;
import top.hazenix.vo.ArticleSlugVO;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Optional;

@RestController
@RequestMapping("/user/articles")
public class ArticleSeoOverlayController {
    private static final String SITE = "https://blog.hazenix.top";
    private static final String ARTICLE_PREFIX = "/article/";
    private static final DateTimeFormatter SNAPSHOT_REVISION = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final String UTF8_HTML = "text/html;charset=UTF-8";
    private static final String[] SITE_PAGES = {
            "/", "/home", "/articles", "/categories", "/tags", "/friend-links",
            "/tree-hole", "/messageboard", "/about"
    };

    private final ArticleService articleService;

    public ArticleSeoOverlayController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @GetMapping(value = "/seo-sitemap", produces = "application/xml;charset=UTF-8")
    public String sitemap() {
        var urls = new LinkedHashMap<String, String>();
        for (String path : SITE_PAGES) {
            urls.put(path, "");
        }
        for (var article : articleService.getPublishedArticleSlugs()) {
            if (article.getId() == 1L) continue;
            String lastmod = article.getUpdateTime() == null ? "" : article.getUpdateTime().toLocalDate().toString();
            urls.putIfAbsent(articlePath(article), lastmod);
        }

        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        urls.forEach((path, lastmod) -> {
            xml.append("<url><loc>").append(SITE).append(path).append("</loc>");
            if (!lastmod.isEmpty()) xml.append("<lastmod>").append(lastmod).append("</lastmod>");
            xml.append("</url>\n");
        });
        return xml.append("</urlset>\n").toString();
    }

    @GetMapping("/seo-status")
    public ResponseEntity<Void> status(@RequestHeader("X-Article-URI") String requestUri) {
        return publishedArticle(requestUri)
                .map(article -> ResponseEntity.noContent()
                        .header("X-Blog-Article-Path", articlePath(article))
                        .header("X-Blog-Article-Revision", article.getUpdateTime() == null
                                ? "undated" : article.getUpdateTime().format(SNAPSHOT_REVISION))
                        .header("X-Blog-Article-Canonical", article.getId() == 1L ? "/messageboard" : articlePath(article))
                        .<Void>build())
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    @GetMapping(value = "/seo-html", produces = UTF8_HTML)
    public ResponseEntity<String> articleHtml(@RequestHeader("X-Article-URI") String requestUri) {
        return publishedArticle(requestUri).map(entry -> {
            var article = articleService.getArticleDetail(entry.getId());
            String title = HtmlUtils.htmlEscape(article.getTitle());
            String content = HtmlUtils.htmlEscape(article.getContent() == null ? "" : article.getContent());
            String canonical = SITE + (entry.getId() == 1L ? "/messageboard" : articlePath(entry));
            String jsonTitle = new String(JsonStringEncoder.getInstance().quoteAsString(article.getTitle()))
                    .replace("<", "\\u003c");
            return ResponseEntity.ok("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>" + title + "</title><link rel=\"canonical\" href=\"" + canonical + "\">"
                    + "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\","
                    + "\"@type\":\"Article\",\"headline\":\"" + jsonTitle + "\",\"url\":\"" + canonical + "\"}</script>"
                    + "<main><article><h1>" + title + "</h1><div style=\"white-space:pre-wrap\">"
                    + content + "</div></article></main></html>");
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<ArticleSlugVO> publishedArticle(String requestUri) {
        try {
            String path = URI.create(requestUri).getRawPath();
            if (path == null || !path.startsWith(ARTICLE_PREFIX)) return Optional.empty();
            String identifier = UriUtils.decode(path.substring(ARTICLE_PREFIX.length()), StandardCharsets.UTF_8);
            if (identifier.endsWith("/")) identifier = identifier.substring(0, identifier.length() - 1);
            if (identifier.matches("[0-9]+")) {
                long id = Long.parseLong(identifier);
                return articleService.getPublishedArticleSlugs().stream().filter(article -> article.getId() == id).findFirst();
            }
            if (!isArticleSlug(identifier)) return Optional.empty();
            String slug = identifier;
            return articleService.getPublishedArticleSlugs().stream().filter(article -> slug.equals(article.getSlug())).findFirst();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String articlePath(ArticleSlugVO article) {
        String identifier = isArticleSlug(article.getSlug()) ? article.getSlug() : article.getId().toString();
        return ARTICLE_PREFIX + UriUtils.encode(identifier, StandardCharsets.UTF_8);
    }

    private boolean isArticleSlug(String slug) {
        return slug != null && !slug.isBlank() && !slug.matches("[0-9]+")
                && !slug.equals(".") && !slug.equals("..")
                && !slug.contains("/") && !slug.contains("\\\\") && slug.chars().noneMatch(Character::isISOControl);
    }
}
