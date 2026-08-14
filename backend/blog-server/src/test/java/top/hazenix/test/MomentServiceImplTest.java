package top.hazenix.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.Invocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import top.hazenix.dto.DeleteMomentsDTO;
import top.hazenix.dto.MomentDTO;
import top.hazenix.entity.Article;
import top.hazenix.exception.BussinessException;
import top.hazenix.mapper.ArticleMapper;
import top.hazenix.mapper.ArticleTagsMapper;
import top.hazenix.mapper.CategoryMapper;
import top.hazenix.mapper.MomentLikeMapper;
import top.hazenix.mapper.TagsMapper;
import top.hazenix.result.PageResult;
import top.hazenix.service.MomentService;
import top.hazenix.service.impl.MomentServiceImpl;
import top.hazenix.vo.MomentVO;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MomentServiceImplTest {

    private static final Integer MOMENT_CATEGORY_ID = 9;

    private ArticleMapper articleMapper;
    private ArticleTagsMapper articleTagsMapper;
    private MomentLikeMapper momentLikeMapper;
    private CategoryMapper categoryMapper;
    private TagsMapper tagsMapper;
    private MomentServiceImpl service;

    @BeforeEach
    void setUp() {
        // pageMoments 的签名会随标签过滤修复变化；按方法名返回空 Page，
        // 让测试聚焦 Service 到 Mapper 的真实参数边界。
        articleMapper = mock(ArticleMapper.class, invocation -> {
            if ("pageMoments".equals(invocation.getMethod().getName())) {
                return new Page<MomentVO>();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        articleTagsMapper = mock(ArticleTagsMapper.class);
        momentLikeMapper = mock(MomentLikeMapper.class);
        categoryMapper = mock(CategoryMapper.class);
        tagsMapper = mock(TagsMapper.class);
        service = new MomentServiceImpl(
                articleMapper,
                articleTagsMapper,
                momentLikeMapper,
                categoryMapper,
                tagsMapper,
                new ObjectMapper()
        );
        when(categoryMapper.getMomentCategoryId()).thenReturn(MOMENT_CATEGORY_ID);
    }

    @AfterEach
    void clearPageHelperState() {
        PageHelper.clearPage();
    }

    @Test
    void pageQueryUser_passesTagNameToMomentQuery() {
        PageResult result = service.pageQueryUser(1, 10, "Java", "127.0.0.1");

        Invocation pageCall = mockingDetails(articleMapper).getInvocations().stream()
                .filter(invocation -> "pageMoments".equals(invocation.getMethod().getName()))
                .findFirst()
                .orElseThrow();
        assertArrayEquals(
                new Object[]{null, 0, MOMENT_CATEGORY_ID, "Java"},
                pageCall.getArguments()
        );
        assertEquals(0, result.getTotal());
    }

    @Test
    void getById_rejectsOrdinaryArticleWithoutIncreasingViews() {
        BussinessException exception = assertThrows(
                BussinessException.class,
                () -> service.getById(100L, "127.0.0.1")
        );

        assertEquals("A02001", exception.getCode());
        verify(articleMapper, never()).incrementViewCount(any());
        verify(articleMapper, never()).getById(any());
    }

    @Test
    void getById_rejectsDraftMomentWithoutIncreasingViews() {
        assertThrows(BussinessException.class, () -> service.getById(101L, "127.0.0.1"));

        verify(articleMapper, never()).incrementViewCount(any());
        verify(articleMapper, never()).getById(any());
    }

    @Test
    void getById_returnsViewCountIncludingCurrentVisit() {
        when(articleMapper.incrementPublishedMomentViewCount(102L, MOMENT_CATEGORY_ID, 0)).thenReturn(1);
        when(articleMapper.getById(102L)).thenReturn(article(102L, MOMENT_CATEGORY_ID, 0, 6));
        when(articleTagsMapper.getTagNamesByArticleId(102L)).thenReturn(Collections.emptyList());

        MomentVO result = service.getById(102L, "127.0.0.1");

        assertEquals(6, result.getViewCount());
        verify(articleMapper).incrementPublishedMomentViewCount(102L, MOMENT_CATEGORY_ID, 0);
    }

    @Test
    void updateMoment_rejectsOrdinaryArticle() {
        MomentDTO dto = momentDto("标题", Collections.emptyList());

        assertThrows(BussinessException.class, () -> service.updateMoment(200L, dto));

        verify(articleMapper).updateMoment(any(), eq(MOMENT_CATEGORY_ID));
        verify(articleTagsMapper, never()).deleteByArticleIds(any());
    }

    @Test
    void updateMoment_canClearOptionalTitleAndAllImages() {
        when(articleMapper.updateMoment(any(), eq(MOMENT_CATEGORY_ID))).thenReturn(1);
        MomentDTO dto = momentDto(null, Collections.emptyList());

        service.updateMoment(201L, dto);

        verify(articleMapper).updateMoment(argThat(article ->
                article.getId().equals(201L)
                        && article.getTitle() == null
                        && "[]".equals(article.getImages())
        ), eq(MOMENT_CATEGORY_ID));
    }

    @Test
    void deleteMoments_rejectsMixedArticleIdsBeforeDeletingAnything() {
        DeleteMomentsDTO dto = new DeleteMomentsDTO();
        dto.setIds(Arrays.asList(300L, 301L));
        when(articleMapper.selectArticlesForUpdate(dto.getIds())).thenReturn(Arrays.asList(
                article(300L, MOMENT_CATEGORY_ID, 0, 0),
                article(301L, 2, 0, 0)
        ));

        assertThrows(BussinessException.class, () -> service.deleteMoments(dto));

        verify(articleTagsMapper, never()).deleteByArticleIds(any());
        verify(articleMapper, never()).deleteByIds(any());
    }

    @Test
    void likeMoment_rejectsOrdinaryArticleBeforeWritingLike() {
        when(articleMapper.getById(400L)).thenReturn(article(400L, 2, 0, 0));

        assertThrows(BussinessException.class, () -> service.likeMoment(400L, "127.0.0.1"));

        verify(momentLikeMapper, never()).insertIfAbsent(any());
        verify(articleMapper, never()).incrementLikeCount(any());
    }

    @Test
    void likeMoment_rejectsDraftMomentBeforeWritingLike() {
        when(articleMapper.getById(401L)).thenReturn(article(401L, MOMENT_CATEGORY_ID, 1, 0));

        assertThrows(BussinessException.class, () -> service.likeMoment(401L, "127.0.0.1"));

        verify(momentLikeMapper, never()).insertIfAbsent(any());
        verify(articleMapper, never()).incrementLikeCount(any());
    }

    @Test
    void deleteMoments_allowsDraftMomentsAndDeletesTagsBeforeArticles() {
        DeleteMomentsDTO dto = new DeleteMomentsDTO();
        dto.setIds(Arrays.asList(302L, 303L));
        when(articleMapper.selectArticlesForUpdate(dto.getIds())).thenReturn(Arrays.asList(
                article(302L, MOMENT_CATEGORY_ID, 1, 0),
                article(303L, MOMENT_CATEGORY_ID, 0, 0)
        ));

        service.deleteMoments(dto);

        verify(articleTagsMapper).deleteByArticleIds(dto.getIds());
        verify(articleMapper).deleteByIds(dto.getIds());
    }

    @Test
    void likeMoment_publishedMomentWritesLikeAndIncrementsCount() {
        when(articleMapper.getById(402L)).thenReturn(article(402L, MOMENT_CATEGORY_ID, 0, 0));
        when(momentLikeMapper.insertIfAbsent(any())).thenReturn(1);
        when(articleMapper.incrementPublishedMomentLikeCount(402L, MOMENT_CATEGORY_ID, 0)).thenReturn(1);

        service.likeMoment(402L, "127.0.0.1");

        verify(momentLikeMapper).insertIfAbsent(argThat(like ->
                like.getArticleId().equals(402L) && "127.0.0.1".equals(like.getIp())
        ));
        verify(articleMapper).incrementPublishedMomentLikeCount(402L, MOMENT_CATEGORY_ID, 0);
    }

    @Test
    void likeMoment_concurrentDuplicateReturnsBusinessErrorWithoutSecondIncrement() {
        when(articleMapper.getById(403L)).thenReturn(article(403L, MOMENT_CATEGORY_ID, 0, 0));
        when(momentLikeMapper.insertIfAbsent(any())).thenReturn(0);

        BussinessException exception = assertThrows(
                BussinessException.class,
                () -> service.likeMoment(403L, "127.0.0.1")
        );

        assertEquals("A03003", exception.getCode());
        verify(articleMapper, never()).incrementPublishedMomentLikeCount(any(), any(), any());
    }

    @Test
    void likeMoment_counterFailureTriggersTransactionRollback() {
        when(articleMapper.getById(404L)).thenReturn(article(404L, MOMENT_CATEGORY_ID, 0, 0));
        when(momentLikeMapper.insertIfAbsent(any())).thenReturn(1);
        when(articleMapper.incrementPublishedMomentLikeCount(404L, MOMENT_CATEGORY_ID, 0))
                .thenThrow(new RuntimeException("counter failed"));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager,
                new AnnotationTransactionAttributeSource()
        ));
        MomentService proxiedService = (MomentService) proxyFactory.getProxy();

        assertThrows(RuntimeException.class,
                () -> proxiedService.likeMoment(404L, "127.0.0.1"));

        verify(transactionManager).rollback(transactionStatus);
    }

    private Article article(Long id, Integer categoryId, Integer status, Integer viewCount) {
        return Article.builder()
                .id(id)
                .categoryId(categoryId)
                .status(status)
                .title("测试手记")
                .content("正文")
                .images("[]")
                .likeCount(0)
                .viewCount(viewCount)
                .build();
    }

    private MomentDTO momentDto(String title, java.util.List<String> images) {
        MomentDTO dto = new MomentDTO();
        dto.setTitle(title);
        dto.setContent("更新后的正文");
        dto.setImageUrls(images);
        dto.setTagIds(Collections.emptyList());
        dto.setStatus(0);
        return dto;
    }
}
