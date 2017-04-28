package me.chanjar.weixin.mp.api.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.chanjar.weixin.common.bean.WxJsapiSignature;
import me.chanjar.weixin.common.bean.result.WxError;
import me.chanjar.weixin.common.exception.WxErrorException;
import me.chanjar.weixin.common.session.StandardSessionManager;
import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.common.util.RandomUtils;
import me.chanjar.weixin.common.util.crypto.SHA1;
import me.chanjar.weixin.common.util.http.*;
import me.chanjar.weixin.mp.api.*;
import me.chanjar.weixin.mp.bean.*;
import me.chanjar.weixin.mp.bean.result.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.locks.Lock;

public abstract class AbstractWxMpServiceImpl<H, P> implements WxMpService, RequestHttp<H, P> {

  private static final JsonParser JSON_PARSER = new JsonParser();

  protected final Logger log = LoggerFactory.getLogger(this.getClass());
  protected WxSessionManager sessionManager = new StandardSessionManager();
  private WxMpConfigStorage wxMpConfigStorage;
  private WxMpKefuService kefuService = new WxMpKefuServiceImpl(this);
  private WxMpMaterialService materialService = new WxMpMaterialServiceImpl(this);
  private WxMpMenuService menuService = new WxMpMenuServiceImpl(this);
  private WxMpUserService userService = new WxMpUserServiceImpl(this);
  private WxMpUserTagService tagService = new WxMpUserTagServiceImpl(this);
  private WxMpQrcodeService qrCodeService = new WxMpQrcodeServiceImpl(this);
  private WxMpCardService cardService = new WxMpCardServiceImpl(this);
  private WxMpStoreService storeService = new WxMpStoreServiceImpl(this);
  private WxMpDataCubeService dataCubeService = new WxMpDataCubeServiceImpl(this);
  private WxMpUserBlacklistService blackListService = new WxMpUserBlacklistServiceImpl(this);
  private WxMpTemplateMsgService templateMsgService = new WxMpTemplateMsgServiceImpl(this);
  private WxMpDeviceService deviceService = new WxMpDeviceServiceImpl(this);

  private int retrySleepMillis = 1000;
  private int maxRetryTimes = 5;

  @Override
  public boolean checkSignature(String timestamp, String nonce, String signature) {
    try {
      return SHA1.gen(this.getWxMpConfigStorage().getToken(), timestamp, nonce)
        .equals(signature);
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public String getJsapiTicket() throws WxErrorException {
    return getJsapiTicket(false);
  }

  @Override
  public String getJsapiTicket(boolean forceRefresh) throws WxErrorException {
    Lock lock = this.getWxMpConfigStorage().getJsapiTicketLock();
    try {
      lock.lock();

      if (forceRefresh) {
        this.getWxMpConfigStorage().expireJsapiTicket();
      }

      if (this.getWxMpConfigStorage().isJsapiTicketExpired()) {
        String url = "https://api.weixin.qq.com/cgi-bin/ticket/getticket?type=jsapi";
        String responseContent = execute(new SimpleGetRequestExecutor(), url, null);
        JsonElement tmpJsonElement = JSON_PARSER.parse(responseContent);
        JsonObject tmpJsonObject = tmpJsonElement.getAsJsonObject();
        String jsapiTicket = tmpJsonObject.get("ticket").getAsString();
        int expiresInSeconds = tmpJsonObject.get("expires_in").getAsInt();
        this.getWxMpConfigStorage().updateJsapiTicket(jsapiTicket, expiresInSeconds);
      }
    } finally {
      lock.unlock();
    }
    return this.getWxMpConfigStorage().getJsapiTicket();
  }

  @Override
  public WxJsapiSignature createJsapiSignature(String url) throws WxErrorException {
    long timestamp = System.currentTimeMillis() / 1000;
    String noncestr = RandomUtils.getRandomStr();
    String jsapiTicket = getJsapiTicket(false);
    String signature = SHA1.genWithAmple("jsapi_ticket=" + jsapiTicket,
      "noncestr=" + noncestr, "timestamp=" + timestamp, "url=" + url);
    WxJsapiSignature jsapiSignature = new WxJsapiSignature();
    jsapiSignature.setAppId(this.getWxMpConfigStorage().getAppId());
    jsapiSignature.setTimestamp(timestamp);
    jsapiSignature.setNonceStr(noncestr);
    jsapiSignature.setUrl(url);
    jsapiSignature.setSignature(signature);
    return jsapiSignature;
  }

  @Override
  public String getAccessToken() throws WxErrorException {
    return getAccessToken(false);
  }

  @Override
  public WxMpMassUploadResult massNewsUpload(WxMpMassNews news) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/media/uploadnews";
    String responseContent = this.post(url, news.toJson());
    return WxMpMassUploadResult.fromJson(responseContent);
  }

  @Override
  public WxMpMassUploadResult massVideoUpload(WxMpMassVideo video) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/media/uploadvideo";
    String responseContent = this.post(url, video.toJson());
    return WxMpMassUploadResult.fromJson(responseContent);
  }

  @Override
  public WxMpMassSendResult massGroupMessageSend(WxMpMassTagMessage message) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/message/mass/sendall";
    String responseContent = this.post(url, message.toJson());
    return WxMpMassSendResult.fromJson(responseContent);
  }

  @Override
  public WxMpMassSendResult massOpenIdsMessageSend(WxMpMassOpenIdsMessage message) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/message/mass/send";
    String responseContent = this.post(url, message.toJson());
    return WxMpMassSendResult.fromJson(responseContent);
  }

  @Override
  public WxMpMassSendResult massMessagePreview(WxMpMassPreviewMessage wxMpMassPreviewMessage) throws Exception {
    String url = "https://api.weixin.qq.com/cgi-bin/message/mass/preview";
    String responseContent = this.post(url, wxMpMassPreviewMessage.toJson());
    return WxMpMassSendResult.fromJson(responseContent);
  }

  @Override
  public String shortUrl(String long_url) throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/shorturl";
    JsonObject o = new JsonObject();
    o.addProperty("action", "long2short");
    o.addProperty("long_url", long_url);
    String responseContent = this.post(url, o.toString());
    JsonElement tmpJsonElement = JSON_PARSER.parse(responseContent);
    return tmpJsonElement.getAsJsonObject().get("short_url").getAsString();
  }

  @Override
  public WxMpSemanticQueryResult semanticQuery(WxMpSemanticQuery semanticQuery) throws WxErrorException {
    String url = "https://api.weixin.qq.com/semantic/semproxy/search";
    String responseContent = this.post(url, semanticQuery.toJson());
    return WxMpSemanticQueryResult.fromJson(responseContent);
  }

  @Override
  public String oauth2buildAuthorizationUrl(String redirectURI, String scope, String state) {
    StringBuilder url = new StringBuilder();
    url.append("https://open.weixin.qq.com/connect/oauth2/authorize?");
    url.append("appid=").append(this.getWxMpConfigStorage().getAppId());
    url.append("&redirect_uri=").append(URIUtil.encodeURIComponent(redirectURI));
    url.append("&response_type=code");
    url.append("&scope=").append(scope);
    if (state != null) {
      url.append("&state=").append(state);
    }
    url.append("#wechat_redirect");
    return url.toString();
  }

  @Override
  public String buildQrConnectUrl(String redirectURI, String scope,
                                  String state) {
    StringBuilder url = new StringBuilder();
    url.append("https://open.weixin.qq.com/connect/qrconnect?");
    url.append("appid=").append(this.getWxMpConfigStorage().getAppId());
    url.append("&redirect_uri=").append(URIUtil.encodeURIComponent(redirectURI));
    url.append("&response_type=code");
    url.append("&scope=").append(scope);
    if (state != null) {
      url.append("&state=").append(state);
    }

    url.append("#wechat_redirect");
    return url.toString();
  }

  private WxMpOAuth2AccessToken getOAuth2AccessToken(StringBuilder url) throws WxErrorException {
    try {
      RequestExecutor<String, String> executor = new SimpleGetRequestExecutor();
      String responseText = executor.execute(this, url.toString(), null);
      return WxMpOAuth2AccessToken.fromJson(responseText);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public WxMpOAuth2AccessToken oauth2getAccessToken(String code) throws WxErrorException {
    StringBuilder url = new StringBuilder();
    url.append("https://api.weixin.qq.com/sns/oauth2/access_token?");
    url.append("appid=").append(this.getWxMpConfigStorage().getAppId());
    url.append("&secret=").append(this.getWxMpConfigStorage().getSecret());
    url.append("&code=").append(code);
    url.append("&grant_type=authorization_code");

    return this.getOAuth2AccessToken(url);
  }

  @Override
  public WxMpOAuth2AccessToken oauth2refreshAccessToken(String refreshToken) throws WxErrorException {
    StringBuilder url = new StringBuilder();
    url.append("https://api.weixin.qq.com/sns/oauth2/refresh_token?");
    url.append("appid=").append(this.getWxMpConfigStorage().getAppId());
    url.append("&grant_type=refresh_token");
    url.append("&refresh_token=").append(refreshToken);

    return this.getOAuth2AccessToken(url);
  }

  @Override
  public WxMpUser oauth2getUserInfo(WxMpOAuth2AccessToken oAuth2AccessToken, String lang) throws WxErrorException {
    StringBuilder url = new StringBuilder();
    url.append("https://api.weixin.qq.com/sns/userinfo?");
    url.append("access_token=").append(oAuth2AccessToken.getAccessToken());
    url.append("&openid=").append(oAuth2AccessToken.getOpenId());
    if (lang == null) {
      url.append("&lang=zh_CN");
    } else {
      url.append("&lang=").append(lang);
    }

    try {
      RequestExecutor<String, String> executor = new SimpleGetRequestExecutor();
      String responseText = executor.execute(this, url.toString(), null);
      return WxMpUser.fromJson(responseText);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean oauth2validateAccessToken(WxMpOAuth2AccessToken oAuth2AccessToken) {
    StringBuilder url = new StringBuilder();
    url.append("https://api.weixin.qq.com/sns/auth?");
    url.append("access_token=").append(oAuth2AccessToken.getAccessToken());
    url.append("&openid=").append(oAuth2AccessToken.getOpenId());

    try {
      RequestExecutor<String, String> executor = new SimpleGetRequestExecutor();
      executor.execute(this, url.toString(), null);
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (WxErrorException e) {
      return false;
    }
    return true;
  }

  @Override
  public String[] getCallbackIP() throws WxErrorException {
    String url = "https://api.weixin.qq.com/cgi-bin/getcallbackip";
    String responseContent = get(url, null);
    JsonElement tmpJsonElement = JSON_PARSER.parse(responseContent);
    JsonArray ipList = tmpJsonElement.getAsJsonObject().get("ip_list").getAsJsonArray();
    String[] ipArray = new String[ipList.size()];
    for (int i = 0; i < ipList.size(); i++) {
      ipArray[i] = ipList.get(i).getAsString();
    }
    return ipArray;
  }

  @Override
  public String get(String url, String queryParam) throws WxErrorException {
    return execute(new SimpleGetRequestExecutor(), url, queryParam);
  }

  @Override
  public String post(String url, String postData) throws WxErrorException {
    return execute(new SimplePostRequestExecutor(), url, postData);
  }

  /**
   * 向微信端发送请求，在这里执行的策略是当发生access_token过期时才去刷新，然后重新执行请求，而不是全局定时请求
   */
  public <T, E> T execute(RequestExecutor<T, E> executor, String uri, E data) throws WxErrorException {
    int retryTimes = 0;
    do {
      try {
        return this.executeInternal(executor, uri, data);
      } catch (WxErrorException e) {
        if (retryTimes + 1 > this.maxRetryTimes) {
          this.log.warn("重试达到最大次数【{}】", maxRetryTimes);
          //最后一次重试失败后，直接抛出异常，不再等待
          throw new RuntimeException("微信服务端异常，超出重试次数");
        }

        WxError error = e.getError();
        // -1 系统繁忙, 1000ms后重试
        if (error.getErrorCode() == -1) {
          int sleepMillis = this.retrySleepMillis * (1 << retryTimes);
          try {
            this.log.warn("微信系统繁忙，{} ms 后重试(第{}次)", sleepMillis, retryTimes + 1);
            Thread.sleep(sleepMillis);
          } catch (InterruptedException e1) {
            throw new RuntimeException(e1);
          }
        } else {
          throw e;
        }
      }
    } while (retryTimes++ < this.maxRetryTimes);

    this.log.warn("重试达到最大次数【{}】", this.maxRetryTimes);
    throw new RuntimeException("微信服务端异常，超出重试次数");
  }

  public synchronized <T, E> T executeInternal(RequestExecutor<T, E> executor, String uri, E data) throws WxErrorException {
    if (uri.contains("access_token=")) {
      throw new IllegalArgumentException("uri参数中不允许有access_token: " + uri);
    }
    String accessToken = getAccessToken(false);

    String uriWithAccessToken = uri + (uri.contains("?") ? "&" : "?") + "access_token=" + accessToken;

    try {
      T result = executor.execute(this, uriWithAccessToken, data);
      this.log.debug("\n[URL]:  {}\n[PARAMS]: {}\n[RESPONSE]: {}", uriWithAccessToken, data, result);
      return result;
    } catch (WxErrorException e) {
      WxError error = e.getError();
      /*
       * 发生以下情况时尝试刷新access_token
       * 40001 获取access_token时AppSecret错误，或者access_token无效
       * 42001 access_token超时
       * 40014 不合法的access_token，请开发者认真比对access_token的有效性（如是否过期），或查看是否正在为恰当的公众号调用接口
       */
      if (error.getErrorCode() == 42001 || error.getErrorCode() == 40001 || error.getErrorCode() == 40014) {
        // 强制设置wxMpConfigStorage它的access token过期了，这样在下一次请求里就会刷新access token
        this.getWxMpConfigStorage().expireAccessToken();
        if (this.getWxMpConfigStorage().autoRefreshToken()) {
          return this.execute(executor, uri, data);
        }
      }

      if (error.getErrorCode() != 0) {
        this.log.error("\n[URL]:  {}\n[PARAMS]: {}\n[RESPONSE]: {}", uriWithAccessToken, data, error);
        throw new WxErrorException(error);
      }
      return null;
    } catch (IOException e) {
      this.log.error("\n[URL]:  {}\n[PARAMS]: {}\n[EXCEPTION]: {}", uriWithAccessToken, data, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  @Override
  public WxMpConfigStorage getWxMpConfigStorage() {
    return this.wxMpConfigStorage;
  }

  @Override
  public void setWxMpConfigStorage(WxMpConfigStorage wxConfigProvider) {
    this.wxMpConfigStorage = wxConfigProvider;
    this.initHttp();
  }

  @Override
  public void setRetrySleepMillis(int retrySleepMillis) {
    this.retrySleepMillis = retrySleepMillis;
  }

  @Override
  public void setMaxRetryTimes(int maxRetryTimes) {
    this.maxRetryTimes = maxRetryTimes;
  }

  @Override
  public WxMpKefuService getKefuService() {
    return this.kefuService;
  }

  @Override
  public WxMpMaterialService getMaterialService() {
    return this.materialService;
  }

  @Override
  public WxMpMenuService getMenuService() {
    return this.menuService;
  }

  @Override
  public WxMpUserService getUserService() {
    return this.userService;
  }

  @Override
  public WxMpUserTagService getUserTagService() {
    return this.tagService;
  }

  @Override
  public WxMpQrcodeService getQrcodeService() {
    return this.qrCodeService;
  }

  @Override
  public WxMpCardService getCardService() {
    return this.cardService;
  }

  @Override
  public WxMpDataCubeService getDataCubeService() {
    return this.dataCubeService;
  }

  @Override
  public WxMpUserBlacklistService getBlackListService() {
    return this.blackListService;
  }

  @Override
  public WxMpStoreService getStoreService() {
    return this.storeService;
  }

  @Override
  public WxMpTemplateMsgService getTemplateMsgService() {
    return this.templateMsgService;
  }

  @Override
  public WxMpDeviceService getDeviceService() {
    return this.deviceService;
  }
}
