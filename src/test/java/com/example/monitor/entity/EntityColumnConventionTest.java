package com.example.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/pitfalls.md} の列定義の決まりを、すべての {@code @Entity} について確かめる。
 *
 * <p>どちらの決まりも、破っても新しい DB（開発・テスト）では何も起きず、既存データのある本番の DB で
 * 初めて壊れる（どちらも実際に発生した）。人の目だけでは見落とすので、決まりから外れたフィールドを
 * 足した時点で {@code ./gradlew build} が落ちるようにする。
 */
@DisplayName("エンティティの列定義の決まり")
class EntityColumnConventionTest {

    /**
     * {@code default} の無い primitive の boolean を許す列（「クラス名.フィールド名」）。
     *
     * <p>{@code MonitoredChannel.currentlyLive} は最初のコミットからあり、本番の DB にも既に列がある
     * （これから既存の行に ALTER で足す列ではない）。今から {@code columnDefinition} を足すと本番の列定義が
     * 変わりうる（{@code ddl-auto: update} は列の型の違いを ALTER する）ので、テストのために変えない。
     * <b>新しいフィールドは、新しいテーブルのものでもここに足さないこと。</b>足すべきなのは {@code columnDefinition} の default。
     */
    private static final Set<String> BOOLEAN_COLUMNS_WITHOUT_DEFAULT = Set.of("MonitoredChannel.currentlyLive");

    /** {@code columnDefinition} の先頭の {@code varchar(N)} と、その N。 */
    private static final Pattern VARCHAR_LENGTH = Pattern.compile("^varchar\\((\\d+)\\)");

    @Nested
    @DisplayName("エンティティの走査")
    class EntityScan {

        @Test
        @DisplayName("正常系：com.example.monitor の下の @Entity を 17 個以上見つける")
        void testMethod01() {
            assertThat(entityClasses()).hasSizeGreaterThanOrEqualTo(17);
        }
    }

    @Nested
    @DisplayName("enum の列")
    class EnumColumns {

        @Test
        @DisplayName("正常系：enum のフィールドには @Enumerated(EnumType.STRING) が付いている")
        void testMethod01() {
            List<String> violations = new ArrayList<>();
            for (Field field : enumFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated == null || enumerated.value() != EnumType.STRING) {
                    violations.add(name(field));
                }
            }

            assertThat(violations)
                    .as("enum のフィールドには @Enumerated(EnumType.STRING) を付ける（付けないと列挙子の順番の数字で保存される）")
                    .isEmpty();
        }

        @Test
        @DisplayName("正常系：enum の列の columnDefinition は varchar(N) で始まる")
        void testMethod02() {
            List<String> violations = new ArrayList<>();
            for (Field field : enumFields()) {
                if (!VARCHAR_LENGTH.matcher(columnDefinition(field)).find()) {
                    violations.add(name(field) + " columnDefinition=\"" + columnDefinition(field) + "\"");
                }
            }

            assertThat(violations)
                    .as("enum の列には @Column(columnDefinition = \"varchar(16)\") のように書く"
                            + "（docs/pitfalls.md「enum の列挙子を増やすと既存 DB で全更新が失敗する」）")
                    .isEmpty();
        }

        @Test
        @DisplayName("正常系：varchar(N) の N は一番長い列挙子の名前の長さ以上")
        void testMethod03() {
            List<String> violations = new ArrayList<>();
            for (Field field : enumFields()) {
                Matcher matcher = VARCHAR_LENGTH.matcher(columnDefinition(field));
                if (!matcher.find()) {
                    continue; // varchar で始まらない列は testMethod02 が挙げる
                }
                int length = Integer.parseInt(matcher.group(1));
                for (Object constant : field.getType().getEnumConstants()) {
                    String constantName = ((Enum<?>) constant).name();
                    if (constantName.length() > length) {
                        violations.add(name(field) + " varchar(" + length + ") < " + constantName);
                    }
                }
            }

            assertThat(violations)
                    .as("列挙子の名前が varchar(N) に収まらないと、その値を保存するときに失敗する。N を広げる")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("boolean の列")
    class BooleanColumns {

        @Test
        @DisplayName("正常系：primitive の boolean の列の columnDefinition には default がある")
        void testMethod01() {
            List<String> violations = new ArrayList<>();
            for (Field field : persistentFields()) {
                if (field.getType() == boolean.class
                        && !BOOLEAN_COLUMNS_WITHOUT_DEFAULT.contains(name(field))
                        && !columnDefinition(field).contains("default")) {
                    violations.add(name(field) + " columnDefinition=\"" + columnDefinition(field) + "\"");
                }
            }

            assertThat(violations)
                    .as("primitive の boolean には @Column(columnDefinition = \"boolean default false\") のように DB の既定値を書く"
                            + "（docs/pitfalls.md「既存データがある状態で NOT NULL の boolean カラムを追加すると失敗する」）")
                    .isEmpty();
        }

        @Test
        @DisplayName("正常系：例外の一覧に挙げた列は実在し、まだ default が無い")
        void testMethod02() {
            List<String> booleanColumnsWithoutDefault = persistentFields().stream()
                    .filter(field -> field.getType() == boolean.class)
                    .filter(field -> !columnDefinition(field).contains("default"))
                    .map(EntityColumnConventionTest::name)
                    .toList();

            assertThat(booleanColumnsWithoutDefault)
                    .as("default を足したか、フィールドを消したら BOOLEAN_COLUMNS_WITHOUT_DEFAULT からも外す")
                    .containsAll(BOOLEAN_COLUMNS_WITHOUT_DEFAULT);
        }
    }

    /**
     * {@code com.example.monitor} の下の {@code @Entity} をクラスパスから探す。
     *
     * <p>一覧を手で持たないのは、エンティティを足したときに一覧へ足し忘れると、そのエンティティが
     * 確かめられないまま通ってしまうため。
     */
    private static List<Class<?>> entityClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return scanner.findCandidateComponents("com.example.monitor").stream()
                .map(BeanDefinition::getBeanClassName)
                .<Class<?>>map(className -> ClassUtils.resolveClassName(className, EntityColumnConventionTest.class.getClassLoader()))
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    /** DB の列になるフィールド（static・transient・{@code @Transient} を除く）。 */
    private static List<Field> persistentFields() {
        List<Field> fields = new ArrayList<>();
        for (Class<?> entity : entityClasses()) {
            for (Field field : entity.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                        || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                fields.add(field);
            }
        }
        return fields;
    }

    private static List<Field> enumFields() {
        return persistentFields().stream().filter(field -> field.getType().isEnum()).toList();
    }

    /** {@code @Column.columnDefinition} を小文字にしたもの。{@code @Column} が無ければ空文字。 */
    private static String columnDefinition(Field field) {
        Column column = field.getAnnotation(Column.class);
        return column == null ? "" : column.columnDefinition().trim().toLowerCase(Locale.ROOT);
    }

    /** 失敗の一覧に出す「クラス名.フィールド名」。 */
    private static String name(Field field) {
        return field.getDeclaringClass().getSimpleName() + "." + field.getName();
    }
}
