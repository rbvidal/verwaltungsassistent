package reasoning.neo4j.config;

/** Configuration for Neo4j connection. */
public class Neo4jProperties {
    private String uri = "bolt://localhost:7687";
    private String username = "neo4j";
    private String password = "password";

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
