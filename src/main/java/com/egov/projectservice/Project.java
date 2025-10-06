package com.egov.projectservice;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.Date;

@Document(collection = "projects")
@Data
public class Project {

   @Id
   String id;
   String ownerPhone;
   String name;
   String description;
   String location;
   Date startDate;
   String status;
   Integer budget;

}
