CREATE DATABASE  IF NOT EXISTS `library_milestone2` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `library_milestone2`;
-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: library_milestone2
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
-- BEFORE RUNNING: MySQL only loads CSV files from its secure_file_priv folder.
--   1. Run: SHOW VARIABLES LIKE 'secure_file_priv';   (on Windows usually C:/ProgramData/MySQL/MySQL Server <version>/Uploads/, a hidden folder)
--   2. Copy authors.csv, books_output.csv, book_authors.csv and borrowers_output.csv from Milestone1/input_csv_files/ into that folder.
--   3. If your folder is different, find-and-replace the path below (C:/ProgramData/MySQL/MySQL Server 26.10/Uploads/) with yours.

--
-- Table structure for table `authors`
--

DROP TABLE IF EXISTS `authors`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `authors` (
  `Author_id` int NOT NULL AUTO_INCREMENT,
  `Name` varchar(255) NOT NULL,
  PRIMARY KEY (`Author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `authors`
--

LOCK TABLES `authors` WRITE;
/*!40000 ALTER TABLE `authors` DISABLE KEYS */;
LOAD DATA INFILE 'C:/ProgramData/MySQL/MySQL Server 26.10/Uploads/authors.csv'
INTO TABLE authors
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS;
/*!40000 ALTER TABLE `authors` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `book`
--

DROP TABLE IF EXISTS `book`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `book` (
  `isbn` varchar(20) NOT NULL,
  `Title` varchar(225) NOT NULL,
  PRIMARY KEY (`isbn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `book`
--

LOCK TABLES `book` WRITE;
/*!40000 ALTER TABLE `book` DISABLE KEYS */;
LOAD DATA INFILE 'C:/ProgramData/MySQL/MySQL Server 26.10/Uploads/books_output.csv'
INTO TABLE book
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS;
/*!40000 ALTER TABLE `book` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `book_authors`
--

DROP TABLE IF EXISTS `book_authors`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `book_authors` (
  `Author_id` int NOT NULL,
  `Isbn` varchar(20) NOT NULL,
  PRIMARY KEY (`Author_id`,`Isbn`),
  KEY `fk_book_authors_book` (`Isbn`),
  CONSTRAINT `fk_book_authors_authors` FOREIGN KEY (`Author_id`) REFERENCES `authors` (`Author_id`),
  CONSTRAINT `fk_book_authors_book` FOREIGN KEY (`Isbn`) REFERENCES `book` (`isbn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `book_authors`
--

LOCK TABLES `book_authors` WRITE;
/*!40000 ALTER TABLE `book_authors` DISABLE KEYS */;
LOAD DATA INFILE 'C:/ProgramData/MySQL/MySQL Server 26.10/Uploads/book_authors.csv'
INTO TABLE book_authors
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS;
/*!40000 ALTER TABLE `book_authors` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `book_loans`
--

DROP TABLE IF EXISTS `book_loans`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `book_loans` (
  `Loan_id` int NOT NULL AUTO_INCREMENT,
  `Isbn` varchar(20) NOT NULL,
  `Card_id` varchar(10) NOT NULL,
  `Date_out` date NOT NULL,
  `Due_date` date NOT NULL,
  `Date_in` date DEFAULT NULL,
  PRIMARY KEY (`Loan_id`),
  KEY `fk_book_loans_book` (`Isbn`),
  KEY `fk_book_loans_borrower` (`Card_id`),
  CONSTRAINT `fk_book_loans_book` FOREIGN KEY (`Isbn`) REFERENCES `book` (`isbn`),
  CONSTRAINT `fk_book_loans_borrower` FOREIGN KEY (`Card_id`) REFERENCES `borrower` (`Card_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `book_loans`
--

LOCK TABLES `book_loans` WRITE;
/*!40000 ALTER TABLE `book_loans` DISABLE KEYS */;
/*!40000 ALTER TABLE `book_loans` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `borrower`
--

DROP TABLE IF EXISTS `borrower`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `borrower` (
  `Card_id` varchar(10) NOT NULL,
  `Ssn` varchar(11) NOT NULL,
  `Bname` varchar(100) NOT NULL,
  `Address` varchar(255) NOT NULL,
  `Phone` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`Card_id`),
  UNIQUE KEY `Ssn` (`Ssn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `borrower`
--

LOCK TABLES `borrower` WRITE;
/*!40000 ALTER TABLE `borrower` DISABLE KEYS */;
LOAD DATA INFILE 'C:/ProgramData/MySQL/MySQL Server 26.10/Uploads/borrowers_output.csv'
INTO TABLE borrower
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS;
/*!40000 ALTER TABLE `borrower` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `fines`
--

DROP TABLE IF EXISTS `fines`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fines` (
  `Loan_id` int NOT NULL,
  `Fine_amt` decimal(10,2) NOT NULL,
  `Paid` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`Loan_id`),
  CONSTRAINT `fk_fines_book_loans` FOREIGN KEY (`Loan_id`) REFERENCES `book_loans` (`Loan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `fines`
--

LOCK TABLES `fines` WRITE;
/*!40000 ALTER TABLE `fines` DISABLE KEYS */;
/*!40000 ALTER TABLE `fines` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-23 18:59:44
