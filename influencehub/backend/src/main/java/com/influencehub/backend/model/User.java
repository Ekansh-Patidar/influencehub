package com.influencehub.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

@Entity
@Data
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Unique at the DB level so concurrent sign-ups cannot create duplicate accounts. */
    @Column(unique = true)
    private String email;

    /** Never serialized into API responses or logs. */
    @JsonIgnore
    @ToString.Exclude
    private String password;
    private String role;

    @Column(columnDefinition = "LONGTEXT")
    private String avatar;
}