package top.hazenix.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.hazenix.dto.MomentDTO;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MomentDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void rejectsMoreThanNineImages() {
        MomentDTO dto = validDto();
        dto.setImageUrls(Collections.nCopies(10, "https://example.com/image.webp"));

        assertTrue(violationProperties(dto).contains("imageUrls"));
    }

    @Test
    void rejectsContentLongerThanTwoThousandCharacters() {
        MomentDTO dto = validDto();
        dto.setContent("字".repeat(2001));

        assertTrue(violationProperties(dto).contains("content"));
    }

    @Test
    void rejectsStatusOutsidePublishedAndDraftValues() {
        MomentDTO dto = validDto();
        dto.setStatus(2);

        assertTrue(violationProperties(dto).contains("status"));
    }

    @Test
    void rejectsMissingStatus() {
        MomentDTO dto = validDto();
        dto.setStatus(null);

        assertTrue(violationProperties(dto).contains("status"));
    }

    @Test
    void acceptsExactDocumentedBoundaries() {
        MomentDTO dto = validDto();
        dto.setContent("字".repeat(2000));
        dto.setImageUrls(Collections.nCopies(9, "https://example.com/image.webp"));
        dto.setStatus(1);

        assertFalse(violationProperties(dto).contains("content"));
        assertFalse(violationProperties(dto).contains("imageUrls"));
        assertFalse(violationProperties(dto).contains("status"));
    }

    private MomentDTO validDto() {
        MomentDTO dto = new MomentDTO();
        dto.setContent("正文");
        dto.setImageUrls(List.of("https://example.com/image.webp"));
        dto.setStatus(0);
        return dto;
    }

    private Set<String> violationProperties(MomentDTO dto) {
        return validator.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
