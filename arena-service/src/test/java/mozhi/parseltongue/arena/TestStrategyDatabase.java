package mozhi.parseltongue.arena;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import java.util.UUID;

record TestStrategyDatabase(StrategyRepository repository, JdbcTemplate jdbc, HikariDataSource source) {
    private static final java.util.List<HikariDataSource> POOLS = new java.util.ArrayList<>();
    static TestStrategyDatabase create() {
        // Keep the DDL connection alive, as production does. H2 2.4 CHECK/IN
        // expressions can retain their creation session after an unpooled close.
        var source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa"); source.setPassword(""); source.setMaximumPoolSize(3);
        POOLS.add(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/arena-migration/V1__create_strategy_library.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        return new TestStrategyDatabase(repository(source), jdbc, source);
    }
    static StrategyRepository repository(HikariDataSource source) {
        try {
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mapper/StrategyMapper.xml"));
            var session = new SqlSessionTemplate(factory.getObject());
            return new StrategyRepository(session.getMapper(StrategyMapper.class), new DataSourceTransactionManager(source));
        } catch (Exception error) { throw new IllegalStateException("Cannot initialize strategy mapper", error); }
    }
    static void closeAll() { POOLS.forEach(HikariDataSource::close); POOLS.clear(); }
}
