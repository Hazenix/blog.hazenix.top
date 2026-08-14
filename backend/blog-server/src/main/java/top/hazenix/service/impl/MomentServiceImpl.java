package top.hazenix.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hazenix.constant.ErrorCode;
import top.hazenix.constant.MessageConstant;
import top.hazenix.constant.RecommendConstants;
import top.hazenix.constant.UserConstants;
import top.hazenix.dto.ArticleTagsDTO;
import top.hazenix.dto.DeleteMomentsDTO;
import top.hazenix.dto.MomentDTO;
import top.hazenix.entity.Article;
import top.hazenix.entity.MomentLike;
import top.hazenix.entity.Tags;
import top.hazenix.exception.BussinessException;
import top.hazenix.mapper.ArticleMapper;
import top.hazenix.mapper.ArticleTagsMapper;
import top.hazenix.mapper.CategoryMapper;
import top.hazenix.mapper.MomentLikeMapper;
import top.hazenix.mapper.TagsMapper;
import top.hazenix.result.PageResult;
import top.hazenix.service.MomentService;
import top.hazenix.util.MomentTitleUtil;
import top.hazenix.vo.MomentVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class MomentServiceImpl implements MomentService {

    private final ArticleMapper articleMapper;
    private final ArticleTagsMapper articleTagsMapper;
    private final MomentLikeMapper momentLikeMapper;
    private final CategoryMapper categoryMapper;
    private final TagsMapper tagsMapper;
    private final ObjectMapper objectMapper;

    private Integer momentCategoryId() {
        Integer id = categoryMapper.getMomentCategoryId();
        if (id == null) throw new BussinessException(ErrorCode.A04001, "手记分类未初始化");
        return id;
    }

    @Override
    @Transactional
    public void createMoment(MomentDTO dto) {
        Article a = Article.builder()
                .userId(UserConstants.DEFAULT_USER_ID)
                .title(dto.getTitle())
                .content(dto.getContent())
                .images(serializeImages(dto.getImageUrls()))
                .coverImage(null)
                .categoryId(momentCategoryId())
                .likeCount(0)
                .favoriteCount(0)
                .viewCount(0)
                .slug(null)
                .status(dto.getStatus() != null ? dto.getStatus() : 0)
                .recommendLevel(RecommendConstants.RECOMMEND_LEVEL_DEFAULT)
                .isTop(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        articleMapper.insert(a);
        saveTags(a.getId(), dto.getTagIds());
    }

    @Override
    @Transactional
    public void updateMoment(Long id, MomentDTO dto) {
        Integer categoryId = momentCategoryId();
        Article a = Article.builder()
                .id(id)
                .title(dto.getTitle())
                .content(dto.getContent())
                .images(serializeImages(dto.getImageUrls()))
                .status(dto.getStatus())
                .updateTime(LocalDateTime.now())
                .build();
        if (articleMapper.updateMoment(a, categoryId) != 1) {
            throw momentNotFound();
        }
        articleTagsMapper.deleteByArticleIds(java.util.List.of(id));
        saveTags(id, dto.getTagIds());
    }

    @Override
    @Transactional
    public void deleteMoments(DeleteMomentsDTO dto) {
        Integer categoryId = momentCategoryId();
        Set<Long> requestedIds = new HashSet<>(dto.getIds());
        List<Article> lockedArticles = articleMapper.selectArticlesForUpdate(dto.getIds());
        boolean allRowsAreMoments = lockedArticles.size() == requestedIds.size()
                && lockedArticles.stream()
                .allMatch(article -> Objects.equals(article.getCategoryId(), categoryId));
        if (!allRowsAreMoments) {
            throw momentNotFound();
        }
        articleTagsMapper.deleteByArticleIds(dto.getIds());
        articleMapper.deleteByIds(dto.getIds());
    }

    @Override
    @Transactional
    public MomentVO getById(Long id, String clientIp) {
        Integer categoryId = momentCategoryId();
        if (articleMapper.incrementPublishedMomentViewCount(id, categoryId, 0) != 1) {
            throw momentNotFound();
        }
        Article a = articleMapper.getById(id);
        return buildVO(a, clientIp);
    }

    @Override
    public PageResult pageQueryUser(Integer page, Integer pageSize, String tagName, String clientIp) {
        // 必须在 startPage 之前解析分类 ID，否则 PageHelper 会拦截 getMomentCategoryId 的 limit 1 查询
        Integer catId = momentCategoryId();
        PageHelper.startPage(page, pageSize);
        Page<MomentVO> res = articleMapper.pageMoments(null, 0, catId, tagName);
        List<MomentVO> list = res.getResult();
        list.forEach(vo -> fillVO(vo, clientIp));
        return new PageResult(res.getTotal(), list);
    }

    @Override
    public PageResult pageQueryAdmin(Integer page, Integer pageSize, String keyword) {
        Integer catId = momentCategoryId();
        PageHelper.startPage(page, pageSize);
        Page<MomentVO> res = articleMapper.pageMoments(keyword, null, catId, null);
        List<MomentVO> list = res.getResult();
        list.forEach(vo -> fillVO(vo, null));
        return new PageResult(res.getTotal(), list);
    }

    @Override
    @Transactional
    public void likeMoment(Long id, String clientIp) {
        requireMomentArticle(id, true);
        if (momentLikeMapper.getByArticleIdAndIp(id, clientIp) != null) {
            throw alreadyLiked();
        }
        int inserted = momentLikeMapper.insertIfAbsent(MomentLike.builder()
                .articleId(id)
                .ip(clientIp)
                .createTime(LocalDateTime.now())
                .build());
        if (inserted == 0) {
            throw alreadyLiked();
        }
        if (articleMapper.incrementPublishedMomentLikeCount(id, momentCategoryId(), 0) != 1) {
            throw momentNotFound();
        }
    }

    @Override
    public List<String> getAllTags() {
        return articleTagsMapper.getMomentTagNames(momentCategoryId());
    }

    private void saveTags(Long articleId, List<Integer> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return;
        List<ArticleTagsDTO> list = new ArrayList<>();
        for (Integer tagId : tagIds) {
            Tags tag = tagsMapper.getById(tagId);
            if (tag == null) continue;
            list.add(ArticleTagsDTO.builder()
                    .articleId(articleId)
                    .tagsId(tagId)
                    .tagsName(tag.getName())
                    .build());
        }
        if (!list.isEmpty()) {
            articleTagsMapper.insertBatch(list);
        }
    }

    private String serializeImages(List<String> urls) {
        if (urls == null) return null;
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> deserializeImages(String images) {
        if (images == null || images.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(images, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private MomentVO buildVO(Article a, String clientIp) {
        MomentVO vo = new MomentVO();
        vo.setId(a.getId());
        vo.setTitle(a.getTitle());
        vo.setContent(a.getContent());
        vo.setImages(deserializeImages(a.getImages()));
        vo.setLikeCount(a.getLikeCount());
        vo.setViewCount(a.getViewCount());
        vo.setStatus(a.getStatus());
        vo.setCreateTime(a.getCreateTime());
        vo.setUpdateTime(a.getUpdateTime());
        fillVO(vo, clientIp);
        return vo;
    }

    private void fillVO(MomentVO vo, String clientIp) {
        if (vo.getImages() == null) {
            vo.setImages(deserializeImages(vo.getImagesJson()));
        }
        vo.setTitle(MomentTitleUtil.fallbackTitle(vo.getTitle(), vo.getContent()));
        vo.setTags(articleTagsMapper.getTagNamesByArticleId(vo.getId()));
        vo.setLiked(clientIp != null && momentLikeMapper.getByArticleIdAndIp(vo.getId(), clientIp) != null);
    }

    private Article requireMomentArticle(Long id, boolean publishedOnly) {
        return requireMomentArticle(id, publishedOnly, momentCategoryId());
    }

    private Article requireMomentArticle(Long id, boolean publishedOnly, Integer categoryId) {
        Article article = articleMapper.getById(id);
        boolean isMoment = article != null && Objects.equals(article.getCategoryId(), categoryId);
        boolean isPublished = article != null && Objects.equals(article.getStatus(), 0);
        if (!isMoment || (publishedOnly && !isPublished)) {
            // 对外统一返回不存在，既阻止跨分类操作，也不泄露草稿是否存在。
            throw momentNotFound();
        }
        return article;
    }

    private BussinessException momentNotFound() {
        return new BussinessException(ErrorCode.A02001, MessageConstant.NOT_FOUND);
    }

    private BussinessException alreadyLiked() {
        return new BussinessException(ErrorCode.A03003, MessageConstant.ALREADY_LIKED);
    }
}
