# Adding a field to a user's profile

`UserProfile` (`user-service/src/main/java/com/learn/userservice/profile/model/UserProfile.java`)
is one row per user with every column nullable — there is no notion of a partially-complete
profile, so adding a piece of information is always just "add a nullable column."

1. Add the field to `UserProfile.java` with its `@Column` annotation. `@Getter`/`@Setter`
   on the class already generates the accessors, so nothing else needs writing by hand.

   ```java
   @Column(name = "company_name", length = 200)
   private String companyName;
   ```

2. Start a database to diff against, if one isn't already running:

   ```bash
   docker compose up -d postgres
   ```

3. Generate the migration:

   ```bash
   ./gradlew :user-service:genMigration -Pname=add_company_name
   ```

   This diffs the JPA entities against the running database (via `liquibase-hibernate7`)
   and writes the difference as a new file under
   `user-service/src/main/resources/db/changelog/generated/`.

4. Review the generated changelog. Confirm it contains only the change you intended (an
   `addColumn`, in this example) before committing it.

5. If this is the *first* file ever generated under `db/changelog/generated/`, add an
   `includeAll` for that path to `db.changelog-master.yaml` so Liquibase picks the
   directory up. After that, every later `genMigration` run just adds another file to a
   directory the changelog already includes.

6. Commit the entity change and the generated changelog together.

Removing a field is the same in reverse: delete the field (and its column reference) from
`UserProfile.java`, run `genMigration` again, and the generated changelog contains the
`dropColumn`.

## Note on database state

`genMigration` diffs against whatever database `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` point
to (defaulting to `jdbc:postgresql://localhost:5432/userdb` / `postgres` / `postgres`).
Make sure that database reflects the schema you're diffing *from* — for example, don't run
it against a database that already has migrations pending that haven't been applied yet,
or the generated changelog will include unrelated changes.
