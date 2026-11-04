package com.example.demospring;

import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.mongodb.DBRef;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.convert.UpdateMapper;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;

class MongoMappingTests {
    @Test
    void referenceUpdatesMapToDbRefsWithoutChangingCredentials() throws Exception {
        MongoMappingContext context = new MongoMappingContext();
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.afterPropertiesSet();
        UpdateMapper mapper = new UpdateMapper(converter);
        DemoEntry entry = new DemoEntry();
        entry.setId("507f1f77bcf86cd799439021");
        entry.setTitle("private title");
        var userEntity = context.getRequiredPersistentEntity(User.class);
        for (Update update : new Update[]{new Update().push("demoEntries", entry),
                new Update().pull("demoEntries", entry)}) {
            Document mapped = mapper.getMappedObject(update.getUpdateObject(), userEntity);
            assertThat(mapped).hasSize(1);
            Document fields = (Document) mapped.values().iterator().next();
            assertThat(fields).containsOnlyKeys("demoEntries");
            assertThat(fields.get("demoEntries")).isEqualTo(new DBRef("demo_entries", new ObjectId(entry.getId())));
        }
        Query query = Query.query(where("id").is("507f1f77bcf86cd799439011").and("password").is("existing-hash"));
        Document mappedQuery = new QueryMapper(converter).getMappedObject(query.getQueryObject(), userEntity);
        assertThat(mappedQuery).containsEntry("_id", new ObjectId("507f1f77bcf86cd799439011"))
                .containsEntry("password", "existing-hash");
    }
}
