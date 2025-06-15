# Dropbox OAuth Debug Information

## 現在の設定確認

App Key: `qlses1g15k57b1v`

## 生成されるOAuth URL

```
https://www.dropbox.com/oauth2/authorize?client_id=qlses1g15k57b1v&response_type=code&redirect_uri=https%3A%2F%2Fsatendroid.example.com%2Foauth&token_access_type=offline
```

## Dropbox App Console設定確認項目

1. **App Console URL**: https://www.dropbox.com/developers/apps
2. **App Key確認**: `qlses1g15k57b1v` が正しいか確認
3. **Redirect URI設定**: `https://satendroid.example.com/oauth` が設定されているか確認
4. **App status**: App が "Development" または "Production" 状態か確認
5. **Permissions**: 必要な権限が有効になっているか確認

## テスト方法

1. 上記のURLを直接ブラウザで開いてみる
2. Dropboxの認証画面が表示されるか確認
3. 認証後にredirect URIにリダイレクトされるか確認

## 考えられる問題

1. **App Key間違い**: local.propertiesの値が実際のApp Keyと異なる
2. **Redirect URI未設定**: Dropbox App ConsoleでRedirect URIが設定されていない
3. **App未承認**: Development modeで作成者以外がアクセスしようとしている
4. **ブラウザ権限**: Android 11+でbrownser起動に必要な権限が不足
5. **Build Config**: Gradle syncが正しく実行されていない

## 次のステップ

1. Dropbox App Consoleで設定を再確認
2. App を一度削除して再作成
3. 新しいApp Keyでlocal.propertiesを更新
4. Gradle clean & rebuild
