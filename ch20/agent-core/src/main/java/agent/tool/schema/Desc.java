package agent.tool.schema;

import java.lang.annotation.*;

/** record 구성요소에 붙여 JSON Schema의 description을 채운다. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Desc {
    String value();
}
