package com.elibrary.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.elibrary.cache.BookCacheService;
import com.elibrary.common.BizException;
import com.elibrary.common.PageResponse;
import com.elibrary.domain.Book;
import com.elibrary.domain.enums.BookType;
import com.elibrary.dto.request.BookQuery;
import com.elibrary.dto.response.BookDetail;
import com.elibrary.dto.response.BookSummary;
import com.elibrary.repository.BookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.elibrary.common.ErrorCode.BOOK_NOT_FOUND;
import static com.elibrary.common.ErrorCode.PARAM_INVALID;

/**
 * 书籍浏览与详情(F1/F2)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookService {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "title", "author");

    private final BookMapper bookMapper;
    private final BookCacheService bookCache;

    /**
     * 浏览书籍:分页 + 关键字(书名/作者)+ 类型/分类过滤 + 排序
     */
    public PageResponse<BookSummary> browse(BookQuery query) {
        validate(query);
        String cacheKey = buildCacheKey(query);
        return bookCache.getBrowsePage(cacheKey, () -> loadBrowsePage(query));
    }

    /**
     * 书籍详情(含实时可借数量)
     */
    public BookDetail detail(long bookId) {
        Optional<BookDetail> detail = bookCache.getDetail(bookId, () -> loadDetail(bookId));
        if (detail.isEmpty()) {
            throw new BizException(BOOK_NOT_FOUND);
        }
        return detail.get();
    }

    private void validate(BookQuery query) {
        if (StringUtils.hasText(query.getType())) {
            BookType type = BookType.fromOrNull(query.getType());
            if (type == null) {
                throw new BizException(PARAM_INVALID, "type 仅支持 EBOOK / JOURNAL");
            }
        }
        if (StringUtils.hasText(query.getSortBy()) && !SORT_FIELDS.contains(query.getSortBy())) {
            throw new BizException(PARAM_INVALID, "sortBy 仅支持 createdAt / title / author");
        }
        if (StringUtils.hasText(query.getOrder())
                && !"asc".equalsIgnoreCase(query.getOrder()) && !"desc".equalsIgnoreCase(query.getOrder())) {
            throw new BizException(PARAM_INVALID, "order 仅支持 asc / desc");
        }
    }

    private PageResponse<BookSummary> loadBrowsePage(BookQuery query) {
        Page<Book> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<Book> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(Book::getTitle, query.getKeyword())
                    .or()
                    .like(Book::getAuthor, query.getKeyword()));
        }
        BookType type = BookType.fromOrNull(query.getType());
        if (type != null) {
            wrapper.eq(Book::getType, type);
        }
        if (StringUtils.hasText(query.getCategory())) {
            wrapper.eq(Book::getCategory, query.getCategory());
        }
        boolean desc = !"asc".equalsIgnoreCase(query.getOrder());
        if (StringUtils.hasText(query.getSortBy())) {
            switch (query.getSortBy()) {
                case "title" -> wrapper.orderBy(true, !desc, Book::getTitle);
                case "author" -> wrapper.orderBy(true, !desc, Book::getAuthor);
                default -> wrapper.orderBy(true, !desc, Book::getCreatedAt);
            }
        } else {
            wrapper.orderByDesc(Book::getCreatedAt);
        }

        Page<Book> result = bookMapper.selectPage(page, wrapper);
        List<BookSummary> items = result.getRecords().stream().map(this::toSummary).toList();
        return PageResponse.of(result, items);
    }

    private Optional<BookDetail> loadDetail(long bookId) {
        Book book = bookMapper.selectById(bookId);
        return book == null ? Optional.empty() : Optional.of(toDetail(book));
    }

    private BookSummary toSummary(Book book) {
        return new BookSummary(book.getId(), book.getTitle(), book.getAuthor(),
                book.getType(), book.getCategory(), book.getCreatedAt());
    }

    private BookDetail toDetail(Book book) {
        return new BookDetail(book.getId(), book.getTitle(), book.getAuthor(), book.getIsbn(),
                book.getType(), book.getCategory(), book.getDescription(),
                book.getTotalCopies(), book.getAvailableCopies(), book.getCreatedAt());
    }

    /**
     * 浏览缓存 key:查询参数规范化后取哈希,避免特殊字符进入 Redis key
     */
    private String buildCacheKey(BookQuery query) {
        String canonical = String.join("|",
                String.valueOf(query.getPage()),
                String.valueOf(query.getSize()),
                String.valueOf(query.getKeyword()),
                String.valueOf(query.getType()),
                String.valueOf(query.getCategory()),
                String.valueOf(query.getSortBy()),
                String.valueOf(query.getOrder()));
        return sha1Hex(canonical);
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
