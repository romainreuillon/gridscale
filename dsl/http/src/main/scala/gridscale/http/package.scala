package gridscale

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore.{ PasswordProtection, PrivateKeyEntry }
import java.security.PrivateKey
import java.security.cert.{ Certificate, CertificateFactory }

import freedsl.errorhandler.ErrorHandler
import org.apache.commons.codec.binary
import org.apache.http.{ HttpEntity, client }
import org.apache.http.client.methods
import org.apache.http.entity.InputStreamEntity
import org.apache.http.message.BasicHttpRequest
import sun.security.provider.X509Factory

import scala.io.Source

import scala.language.{ higherKinds, postfixOps }

package object http {

  import gridscale.http.methods._
  import org.apache.http.{ HttpRequest, HttpResponse, HttpStatus }
  import org.apache.http.client.config.RequestConfig
  import org.apache.http.client.methods.HttpUriRequest
  import org.apache.http.config.SocketConfig
  import org.apache.http.impl.client.{ HttpClients, LaxRedirectStrategy }
  import org.apache.http.impl.conn.BasicHttpClientConnectionManager
  import org.apache.http.protocol.HttpContext
  import gridscale._
  import freedsl.dsl._
  import freedsl.filesystem._
  import org.htmlparser.Parser
  import org.htmlparser.filters.NodeClassFilter
  import org.htmlparser.tags.LinkTag
  import cats._
  import cats.implicits._
  import squants._
  import squants.time.TimeConversions._
  import squants.information.InformationConversions._
  import java.io.IOException
  import java.net.URI
  import org.apache.http.client.methods._
  import scala.util.Try
  import gridscale.tools._
  import java.io.InputStream
  import org.apache.http.config.{ RegistryBuilder, SocketConfig }
  import org.apache.http.conn.socket.ConnectionSocketFactory
  import squants.information.Information

  object HTTP {

    type Headers = Seq[(String, String)]

    object Headers {
      def expectContinue = (org.apache.http.protocol.HTTP.EXPECT_DIRECTIVE, org.apache.http.protocol.HTTP.EXPECT_CONTINUE)
    }

    sealed trait Method
    case class Get(headers: Headers = Seq.empty) extends Method
    case class PropFind(headers: Headers = Seq.empty) extends Method
    case class Delete(headers: Headers = Seq.empty) extends Method
    case class Put(stream: () ⇒ InputStream, headers: Headers = Seq.empty) extends Method
    case class Post(entity: () ⇒ HttpEntity, headers: Headers = Seq.empty) extends Method
    case class MkCol(headers: Headers = Seq.empty) extends Method
    case class Head(headers: Headers = Seq.empty) extends Method
    //
    //    def okClient(server: Server) =
    //      server match {
    //        case s: HTTPServer  ⇒
    //          import okhttp3.OkHttpClient
    //          val client = new OkHttpClient.Builder().connectionPool()
    //          c
    //          httpClient(s.timeout)
    //        case s: HTTPSServer ⇒ HTTPS.newClient(s.socketFactory, s.timeout)
    //      }

    def client(server: Server) =
      server match {
        case s: HTTPServer  ⇒ httpClient(s.timeout)
        case s: HTTPSServer ⇒ HTTPS.newClient(s.socketFactory, s.timeout)
      }

    def requestConfig(timeout: Time) =
      RequestConfig.custom()
        .setSocketTimeout(timeout.toMillis.toInt)
        .setConnectTimeout(timeout.toMillis.toInt)
        .setConnectionRequestTimeout(timeout.toMillis.toInt)
        .build()

    def httpClient(timeout: Time) = {
      def connectionManager(timeout: Time) = {
        val client = new BasicHttpClientConnectionManager()
        val socketConfig = SocketConfig.custom().setSoTimeout(timeout.toMillis.toInt).build()
        client.setSocketConfig(socketConfig)
        client
      }

      def newClient(timeout: Time) =
        HttpClients.custom().
          //setRedirectStrategy(redirectStrategy).
          setConnectionManager(connectionManager(timeout)).
          setDefaultRequestConfig(requestConfig(timeout)).build()

      newClient(timeout)
    }

    def interpreter = new Interpreter {

      def withInputStream[T](server: Server, path: String, f: (HttpRequest, HttpResponse) ⇒ T, method: HTTP.Method, test: Boolean): Try[T] = {
        val uri =
          if (path.isEmpty) server.url else new URI(server.url.toString + path)

        val (methodInstance, headers, closeable) =
          method match {
            case Get(headers)      ⇒ (new HttpGet(uri), headers, None)
            case PropFind(headers) ⇒ (new HttpPropFind(uri), headers, None)
            case Delete(headers)   ⇒ (new HttpDelete(uri), headers, None)
            case MkCol(headers)    ⇒ (new HttpMkCol(uri), headers, None)
            case Head(headers)     ⇒ (new HttpHead(uri), headers, None)
            case Put(is, headers) ⇒
              val putInstance = new HttpPut(uri)
              val createdStream = is()
              putInstance.setEntity(new InputStreamEntity(createdStream))
              (putInstance, headers, Some(createdStream))
            case Post(fentity, headers) ⇒
              val postInstance = new HttpPost(uri)
              postInstance.setEntity(fentity())
              (postInstance, headers, None)
          }

        headers.foreach { case (k, v) ⇒ methodInstance.addHeader(k, v) }

        def testResponse(response: HttpResponse) = {
          def isResponseOk(response: HttpResponse) =
            response.getStatusLine.getStatusCode >= HttpStatus.SC_OK &&
              response.getStatusLine.getStatusCode < HttpStatus.SC_BAD_REQUEST

          if (!isResponseOk(response)) throw new IOException(s"Server responded with an error: ${response.getStatusLine.getStatusCode} ${response.getStatusLine.getReasonPhrase}")
        }

        import util._

        Try {
          try {
            val httpClient = client(server)
            try {
              val response = httpClient.execute(methodInstance)
              try {
                if (test) testResponse(response)
                f(methodInstance, response)
              } finally response.close()
            } finally httpClient.close()
          } finally closeable.foreach(_.close())
        }
      }

      def request[T](server: Server, path: String, f: (HttpRequest, HttpResponse) ⇒ T, method: HTTP.Method, testResponse: Boolean)(implicit context: Context) =
        result(withInputStream(server, path, f, method, testResponse).toEither.leftMap(t ⇒ HTTPError(t)))

      def content(server: Server, path: String, method: HTTP.Method)(implicit context: Context) = {
        def getString(is: InputStream) = new String(getBytes(is, server.bufferSize.toBytes.toInt, server.timeout))
        def getContent(r: HttpResponse) = Option(r.getEntity).map(e ⇒ getString(e.getContent)).getOrElse("")
        withInputStream(server, path, (_, r) ⇒ getContent(r), method, test = true).toEither.leftMap(t ⇒ HTTPError(t))
      }

    }

    case class HTTPError(t: Throwable) extends Exception(t) with Error {
      override def toString = "HTTP error: " + t.toString
    }
  }

  @dsl trait HTTP[M[_]] {
    def request[T](server: Server, path: String, f: (HttpRequest, HttpResponse) ⇒ T, method: HTTP.Method, testResponse: Boolean = true): M[T]
    def content(server: Server, path: String, method: HTTP.Method = HTTP.Get()): M[String]
  }

  sealed trait Server {
    def url: URI
    def timeout: Time
    def bufferSize: Information
  }

  object HTTPServer {
    def apply(url: String, timeout: Time = 1 minutes, bufferSize: Information = 64 kilobytes) =
      new HTTPServer(new URI(url), timeout, bufferSize)
  }

  case class HTTPServer(url: URI, timeout: Time, bufferSize: Information) extends Server

  object HTTPSServer {
    def apply(url: String, socketFactory: HTTPS.SSLSocketFactory, timeout: Time = 1 minutes, bufferSize: Information = 64 kilobytes) =
      new HTTPSServer(new URI(url), socketFactory, timeout, bufferSize)
  }

  case class HTTPSServer(url: URI, socketFactory: HTTPS.SSLSocketFactory, timeout: Time, bufferSize: Information) extends Server

  object Server {
    def copy(s: Server)(url: URI = s.url) =
      s match {
        case s: HTTPServer  ⇒ s.copy(url = url)
        case s: HTTPSServer ⇒ s.copy(url = url)
      }
  }

  def parseHTMLListing(page: String) = {
    val parser = new Parser
    parser.setInputHTML(page)
    val list = parser.extractAllNodesThatMatch(new NodeClassFilter(classOf[LinkTag]))

    list.toNodeArray.flatMap {
      l ⇒
        val entryName = l.getText.substring("a href=\"".size, l.getText.size - 1)
        val isDir = entryName.endsWith("/")
        val name = if (isDir) entryName.substring(0, entryName.length - 1) else entryName
        if (!name.isEmpty && !name.contains("/") && !name.contains("?") && !name.contains("#")) {
          val ret = name.replaceAll("&amp;", "%26")
          Some(
            ListEntry(
              new java.io.File(java.net.URLDecoder.decode(ret, "utf-8")).getPath,
              if (isDir) FileType.Directory else FileType.File,
              None
            )
          )
        } else None
    }.toVector
  }

  def list[M[_]: Monad](server: Server, path: String)(implicit http: HTTP[M]) = http.content(server, path).map(parseHTMLListing)
  def read[M[_]: Monad](server: Server, path: String, method: HTTP.Method = HTTP.Get())(implicit http: HTTP[M]) = http.content(server, path, method)
  def readStream[M[_]: Monad, T](server: Server, path: String, f: InputStream ⇒ T, method: HTTP.Method = HTTP.Get())(implicit http: HTTP[M]): M[T] =
    http.request(server, path, (_, r) ⇒ f(r.getEntity.getContent), method)

  object HTTPS {

    import squants._
    import javax.net.ssl.X509TrustManager
    import java.security.cert.CertificateException
    import java.security.{ KeyStore, SecureRandom }
    import javax.net.ssl._
    import org.apache.http.conn.ssl.{ BrowserCompatHostnameVerifier, SSLConnectionSocketFactory }

    type SSLSocketFactory = (Time ⇒ SSLConnectionSocketFactory)

    def socketFactory(s: Vector[KeyStoreOperations.Storable], password: String) =
      Try { KeyStoreOperations.socketFactory(() ⇒ KeyStoreOperations.createSSLContext(KeyStoreOperations.createKeyStore(s, password), password)) }

    object KeyStoreOperations {

      def createKeyStore(s: Vector[KeyStoreOperations.Storable], password: String) = {
        val keyStore = {
          val ks = KeyStore.getInstance(KeyStore.getDefaultType)
          ks.load(null, password.toCharArray)
          ks
        }

        s.zipWithIndex.foreach { case (s, i) ⇒ store(keyStore, i.toString, s) }
        keyStore
      }

      def store(ks: KeyStore, name: String, t: Storable) = t match {
        case t: Credential ⇒
          val entry = new PrivateKeyEntry(t.privateKey, t.certificateChain.toArray)
          ks.setEntry(name, entry, new PasswordProtection(t.password.toCharArray))
        case t: Certificate ⇒
          ks.setCertificateEntry(name, t.certificate)
      }

      sealed trait Storable
      case class Credential(privateKey: PrivateKey, certificateChain: Vector[java.security.cert.Certificate], password: String) extends Storable
      case class Certificate(certificate: java.security.cert.Certificate) extends Storable
      //case class Key(key: java.security.Key, password: String, certificates: Vector[java.security.cert.Certificate], pat) extends Storable

      def createSSLContext(keyStore: KeyStore, password: String): SSLContext = {
        class TrustManagerDelegate(val mainTrustManager: X509TrustManager, val fallbackTrustManager: X509TrustManager) extends X509TrustManager {
          override def checkClientTrusted(x509Certificates: Array[java.security.cert.X509Certificate], authType: String) =
            try {
              mainTrustManager.checkClientTrusted(x509Certificates, authType)
            } catch {
              case ignored: CertificateException ⇒ fallbackTrustManager.checkClientTrusted(x509Certificates, authType)
            }

          override def checkServerTrusted(x509Certificates: Array[java.security.cert.X509Certificate], authType: String) =
            try {
              mainTrustManager.checkServerTrusted(x509Certificates, authType)
            } catch {
              case ignored: CertificateException ⇒ fallbackTrustManager.checkServerTrusted(x509Certificates, authType)
            }

          override def getAcceptedIssuers() = fallbackTrustManager.getAcceptedIssuers()
        }

        val javaDefaultTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        javaDefaultTrustManager.init(null: KeyStore)

        createSSLContext(
          keyStore,
          new TrustManagerDelegate(
            trustManager(keyStore),
            javaDefaultTrustManager.getTrustManagers()(0).asInstanceOf[X509TrustManager]
          ),
          password
        )
      }

      //def createSSLContext(path: String, password: String): SSLContext = createSSLContext(getKeyStore(path, password), password)

      def createSSLContext(keyStore: KeyStore, trustManager: TrustManager, password: String) = {
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        kmf.init(keyStore, password.toCharArray)
        sslContext.init(kmf.getKeyManagers, Array[TrustManager](trustManager), new SecureRandom())
        sslContext
      }

      def socketFactory(sslContext: () ⇒ SSLContext): SSLSocketFactory =
        (timeout: Time) ⇒
          new org.apache.http.conn.ssl.SSLConnectionSocketFactory(sslContext()) {
            override protected def prepareSocket(socket: SSLSocket) = {
              super.prepareSocket(socket)
              socket.setSoTimeout(timeout.millis.toInt)
            }
          }

    }

    def trustManager(keyStore: KeyStore) = {
      val customCaTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      customCaTrustManager.init(keyStore)
      customCaTrustManager.getTrustManagers()(0).asInstanceOf[X509TrustManager]
    }

    //    def getKeyStore(path: String, password: String) = {
    //      val ks = KeyStore.getInstance("JKS")
    //      val is = this.getClass.getResourceAsStream(path)
    //      try ks.load(is, password.toCharArray())
    //      finally is.close()
    //      ks
    //    }

    def connectionManager(factory: org.apache.http.conn.ssl.SSLConnectionSocketFactory, timeout: Time) = {
      val registry = RegistryBuilder.create[ConnectionSocketFactory]().register("https", factory).build()
      val client = new BasicHttpClientConnectionManager(registry)
      val socketConfig = SocketConfig.custom().setSoTimeout(timeout.toMillis.toInt).build()
      client.setSocketConfig(socketConfig)
      client
    }

    def newClient(factory: SSLSocketFactory, timeout: Time) =
      HttpClients.custom().
        setConnectionManager(connectionManager(factory(timeout), timeout)).
        setDefaultRequestConfig(HTTP.requestConfig(timeout)).build()

    def readPem[M[_]: Monad](pem: java.io.File)(implicit fileSystem: FileSystem[M]) =
      for {
        content ← fileSystem.readStream(pem)(is ⇒ Source.fromInputStream(is).mkString)
      } yield util.Try {
        val stripped = content.replaceAll(X509Factory.BEGIN_CERT, "").replaceAll(X509Factory.END_CERT, "")
        val decoded = new binary.Base64().decode(stripped)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(decoded))
        KeyStoreOperations.Certificate(certificate)
      }

    def readP12[M[_]: Monad](file: java.io.File, password: String)(implicit fileSystem: FileSystem[M]) = {
      def keyStore =
        fileSystem.readStream(file) { is ⇒
          val ks = KeyStore.getInstance("pkcs12")
          ks.load(is, password.toCharArray)
          ks
        }

      def extractCertificate(ks: KeyStore) = util.Try {
        val aliases = ks.aliases

        import java.security.cert._
        import collection.JavaConverters._

        // FIXME GET
        val alias = aliases.asScala.find(e ⇒ ks.isKeyEntry(e)).get

        //if (alias == null) throw new VOMSException("No aliases found inside pkcs12 certificate!")
        val userCert = ks.getCertificate(alias).asInstanceOf[X509Certificate]
        val userKey = ks.getKey(alias, password.toCharArray).asInstanceOf[PrivateKey]
        val userChain = Array[X509Certificate](userCert)

        KeyStoreOperations.Credential(userKey, userChain.toVector, password)

        // Loaded(userCert, userKey, userChain)
      }

      for { ks ← keyStore } yield extractCertificate(ks)
    }

    def readPEMCertificates[M[_]: HTTP: FileSystem: ErrorHandler: Monad](certificateDirectory: java.io.File) = {
      import cats.implicits._

      for {
        certificateFiles ← FileSystem[M].list(certificateDirectory)
        certificates ← certificateFiles.traverse(f ⇒ HTTPS.readPem[M](f)).map(_.flatMap(_.toOption))
      } yield certificates
    }

    // case class Loaded(certficate: X509Certificate, key: PrivateKey, chain: Array[X509Certificate])

    //    def addToKeyStore[M[_]: Monad](pems: Vector[java.io.File], ks: KeyStore = emptyKeyStore)(implicit keyStoreOperations: KeyStoreOperations[M]) = {
    //      for {
    //        (file, i) ← pems.zipWithIndex
    //        pem ← readPem(file).tried
    //      } Try(ks.setCertificateEntry(i.toString, pem))
    //      ks
    //    }
    //
    //    def addToKeyStore(key: PrivateKey, certficate: Vector[Certificate], ks: KeyStore, password: String) = {
    //      val entry = new PrivateKeyEntry(key, certficate.toArray)
    //      ks.setEntry("test", entry, new PasswordProtection(password.toCharArray))
    //    }

  }

}
