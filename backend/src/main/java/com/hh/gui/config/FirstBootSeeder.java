package com.hh.gui.config;

import com.hh.gui.config.AppConfig.PersonConfig;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.User;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.UserRepository;
import com.hh.gui.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time bootstrap: creates the admin account and imports the old YAML
 * search config into the DB, the first time this version of the app starts
 * against a DB with zero users. Never runs again after that (guarded by
 * userRepo.count() == 0), and config/profiles/default.yaml is never read
 * by anything else once this has run.
 */
@Component
public class FirstBootSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstBootSeeder.class);

    private final UserRepository userRepo;
    private final SearchRepository searchRepo;
    private final AuthService authService;

    @Value("${app.search.profiles-dir}")
    private String profilesDir;

    public FirstBootSeeder(UserRepository userRepo, SearchRepository searchRepo, AuthService authService) {
        this.userRepo = userRepo;
        this.searchRepo = searchRepo;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepo.count() > 0) {
            return;
        }

        createAdmin();
        importFromYaml();
    }

    private void createAdmin() {
        String password = authService.generatePassword();
        User admin = new User();
        admin.setUsername("admin");
        admin.setPasswordHash(authService.hash(password));
        admin.setDisplayName("Администратор");
        admin.setRole("admin");
        admin.setActive(true);
        userRepo.save(admin);
        log.warn("=== Создан аккаунт admin, пароль: {} — смените его после первого входа ===", password);
    }

    private void importFromYaml() {
        List<PersonConfig> people = AppConfig.parseYaml(profilesDir);
        if (people.isEmpty()) {
            return;
        }

        for (PersonConfig person : people) {
            String username = uniqueUsername(person.name);
            String password = authService.generatePassword();

            User user = new User();
            user.setUsername(username);
            user.setPasswordHash(authService.hash(password));
            user.setDisplayName(person.name);
            user.setRole("user");
            user.setCity(person.city);
            user.setActive(true);
            User saved = userRepo.save(user);
            log.warn("=== Создан аккаунт {} ({}), пароль: {} — сообщите его пользователю ===",
                username, person.name, password);

            for (AppConfig.SearchConfig yamlSearch : person.searches) {
                SearchConfig search = new SearchConfig();
                search.setUserId(saved.getId());
                search.setName(yamlSearch.name);
                search.setQueries(yamlSearch.queries);
                search.setArea(yamlSearch.area);
                search.setSchedule(yamlSearch.schedule);
                search.setSalaryMin(yamlSearch.salaryMin);
                search.setPriorityDistricts(yamlSearch.priorityDistricts);
                search.setSkills(yamlSearch.skills);
                search.setNotSuitable(yamlSearch.notSuitable);
                search.setExcludeWords(yamlSearch.excludeWords);
                search.setAiNotes(yamlSearch.aiNotes);
                search.setEnabled(true);
                searchRepo.save(search);
            }
            log.info("Импортировано {} поисков для {}", person.searches.size(), person.name);
        }
    }

    private String uniqueUsername(String personName) {
        String base = personName.trim().toLowerCase().replaceAll("\\s+", "_");
        if (base.isEmpty()) base = "user";
        String candidate = base;
        int suffix = 2;
        while (userRepo.existsByUsername(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }
}
